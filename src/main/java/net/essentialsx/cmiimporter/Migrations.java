/*
 * Imports data from a CMI SQLite database into EssentialsX.
 * Copyright (C) 2020 md678685
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package net.essentialsx.cmiimporter;

import co.aikar.idb.DB;
import co.aikar.idb.DbRow;
import com.earth2me.essentials.Essentials;
import com.earth2me.essentials.OfflinePlayer;
import com.earth2me.essentials.User;
import net.ess3.api.MaxMoneyException;
import net.ess3.nms.refl.ReflUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static com.earth2me.essentials.I18n.tl;

public class Migrations {

    private static final Logger logger = Logger.getLogger("EssentialsX-CMI-Importer");

    private static final Method SET_OFFLINE_PLAYER_NAME = ReflUtil.getMethodCached(OfflinePlayer.class, "setName", String.class);

    private static String prefix;

    static void migrateAll(CMIImporter importerPlugin, Plugin essPlugin) {
        if (!(essPlugin instanceof Essentials)) {
            throw new IllegalArgumentException("The currently installed \"Essentials\" plugin isn't actually EssentialsX!");
        }

        Essentials ess = (Essentials) essPlugin;
        prefix = importerPlugin.getDbConfig().getTablePrefix();
        migrateUsers(ess);
        migrateHomes(ess);
        migrateNicknames(ess);
        migrateWarps(ess);
        migrateEconomy(ess);
        migrateLastLogin(ess);
        migrateLastLogout(ess);
        migrateMail(ess);
    }

    static void migrateUsers(Essentials ess) {
        try {
            SET_OFFLINE_PLAYER_NAME.setAccessible(true);
            List<DbRow> results = DB.getResults("SELECT player_uuid, username, FakeAccount FROM " + table("users") + " WHERE player_uuid IS NOT NULL AND username IS NOT NULL");
            for (DbRow row : results) {
                UUID uuid = UUID.fromString(row.getString("player_uuid"));
                String username = row.getString("username");
                boolean isNpc = row.get("FakeAccount");

                if (!ess.getUserMap().userExists(uuid)) {
                    OfflinePlayer player = new OfflinePlayer(uuid, Bukkit.getServer());
                    SET_OFFLINE_PLAYER_NAME.invoke(player, username);

                    User user = new User(player, ess);
                    user.setLastAccountName(username);
                    if (isNpc) {
                        user.setNPC(true);
                    }
                    user.save();
                }
            }
        } catch (SQLException | IllegalAccessException | InvocationTargetException e) {
            e.printStackTrace();
        }
    }

    static void migrateHomes(Essentials ess) {
        final String homeLocSeparator = ":";
        try {
            List<DbRow> results = DB.getResults("SELECT player_uuid, Homes FROM " + table("users") + " WHERE player_uuid IS NOT NULL AND Homes IS NOT NULL");
            for (DbRow row : results) {
                UUID uuid = UUID.fromString(row.getString("player_uuid"));
                User user = ess.getUser(uuid);

                for (Map.Entry<String, String> entry : Util.parseMap(row.getString("Homes")).entrySet()) {
                    String name = entry.getKey();
                    String loc = entry.getValue();
                    try {
                        user.setHome(name, Util.parseLocation(loc, homeLocSeparator, true));
                    } catch (Exception ex) {
                        logger.warning("Couldn't set home: " + name + " for " + user.getLastAccountName());
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    static void migrateNicknames(Essentials ess) {
        try {
            List<DbRow> results = DB.getResults("SELECT player_uuid, nickname FROM " + table("users") + " WHERE player_uuid IS NOT NULL AND nickname IS NOT NULL");
            for (DbRow row : results) {
                UUID uuid = UUID.fromString(row.getString("player_uuid"));
                User user = ess.getUser(uuid);
                String nickname = row.getString("nickname");
                if (nickname != null) {
                    user.setNickname(nickname);
                }
            }
        } catch (SQLException throwables) {
            throwables.printStackTrace();
        }
    }

    static void migrateWarps(Essentials ess) {
        final String warpLocSeparator = ";";
        try {
            File warpsFile = new File(ess.getDataFolder(), "../CMI/warps.yml");
            YamlConfiguration warpsConfig = YamlConfiguration.loadConfiguration(warpsFile);
            for (String key : warpsConfig.getKeys(false)) {
                String locString = warpsConfig.getString(key + ".Location");
                if (locString != null) {
                    Location loc = Util.parseLocation(locString, warpLocSeparator, false);
                    try {
                        ess.getWarps().setWarp(null, key, loc);
                    } catch (Exception ex) {
                        logger.warning("Couldn't migrate warp: " + key);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static void migrateEconomy(Essentials ess) {
        try {
            List<DbRow> results = DB.getResults("SELECT player_uuid, Balance FROM " + table("users") + " WHERE player_uuid IS NOT NULL AND Balance IS NOT NULL");
            for (DbRow row : results) {
                UUID uuid = UUID.fromString(row.getString("player_uuid"));
                User user = ess.getUser(uuid);
                // Not using valueOf() because it returns scientific notation and Glare isn't advanced enough to find a way to fix this
                String value = row.getDbl("Balance", 0.0).toString();
                BigDecimal bal = new BigDecimal(value);
                user.setMoney(bal);
            }
        } catch (SQLException | MaxMoneyException ex) {
            ex.printStackTrace();
        }
    }

    static void migrateLastLogin(Essentials ess) {
        try {
            List<DbRow> results = DB.getResults("SELECT player_uuid, LastLoginTime FROM " + table("users") + " WHERE player_uuid IS NOT NULL AND LastLoginTime IS NOT NULL");
            for (DbRow row : results) {
                UUID uuid = UUID.fromString(row.getString("player_uuid"));
                User user = ess.getUser(uuid);
                try {
                    long lastLoginTime = row.getLong("LastLoginTime");
                    user.setLastLogin(lastLoginTime);
                } catch (Exception ex) {
                    logger.warning("Could not set the last login time for: " + user.getLastAccountName());
                }
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    // I have this copied to a different method because there might be a chance the last login time / logout time is null separate if a crash were to occur
    static void migrateLastLogout(Essentials ess) {
        try {
            List<DbRow> results = DB.getResults("SELECT player_uuid, LastLogoffTime FROM " + table("users") + " WHERE player_uuid IS NOT NULL AND LastLogoffTime IS NOT NULL");
            for (DbRow row : results) {
                UUID uuid = UUID.fromString(row.getString("player_uuid"));
                User user = ess.getUser(uuid);
                try {
                    long lastLogoffTime = row.getLong("LastLogoffTime");
                    user.setLastLogout(lastLogoffTime);
                } catch (Exception ex) {
                    logger.warning("Could not set the last logoff time for: " + user.getLastAccountName());
                }
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    static void migrateMail(Essentials ess) {
        try {
            List<DbRow> results = DB.getResults("SELECT player_uuid, Mail FROM " + table("users") + " WHERE player_uuid IS NOT NULL AND Mail IS NOT NULL");
            for (DbRow row : results) {
                UUID uuid = UUID.fromString(row.get("player_uuid"));
                User user = ess.getUser(uuid);
                List<List<String>> mails = Util.parseLists(row.getString("Mail"), ";", ":");
                for (List<String> mail : mails) {
                    String sender = mail.get(0);
                    // CMI replaces ";" with "T7C" and ":" with "T8C" when storing message contents
                    String content = mail.get(2).replace("T7C", ";").replace("T8C", ":");
                    String mailFormat = tl("mailFormat", sender, content);
                    user.addMail(tl("mailMessage", mailFormat));
                }
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }


    private static String table(String table) {
        return prefix + table;
    }

}
