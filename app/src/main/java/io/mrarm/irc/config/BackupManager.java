package io.mrarm.irc.config;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.preference.PreferenceManager;
import android.util.Log;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import net.lingala.zip4j.core.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.util.Zip4jConstants;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.security.GeneralSecurityException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.mrarm.irc.connection.ServerConnectionManager;
import io.mrarm.irc.setting.ListWithCustomSetting;
import io.mrarm.irc.storage.MessageStorageRepository;
import io.mrarm.irc.storage.db.ChatLogDatabase;
import io.mrarm.irc.util.theme.ThemeInfo;
import io.mrarm.irc.util.theme.ThemeManager;

public class BackupManager {

    private static final String BACKUP_PREFERENCES_PATH = "preferences.json";
    private static final String BACKUP_PREF_VALUES_PREFIX = "pref_values/";
    private static final String BACKUP_SERVER_PREFIX = "servers/server-";
    private static final String BACKUP_SERVER_SUFFIX = ".json";
    private static final String BACKUP_SERVER_CERTS_PREFIX = "servers/server-certs-";
    private static final String BACKUP_SERVER_CERTS_SUFFIX = ".jks";
    private static final String BACKUP_NOTIFICATION_RULES_PATH = "notification_rules.json";
    private static final String BACKUP_COMMAND_ALIASES_PATH = "command_aliases.json";
    private static final String BACKUP_THEME_PREFIX = "themes/theme-";
    private static final String BACKUP_THEME_SUFFIX = ".json";
    private static final String CHATLOG_DB_PATH = "chatlogs.db";


    /**
     * Creates a full application backup as a ZIP archive.
     * <p>
     * Backup contents (in order of importance): <br>
     * - SharedPreferences (preferences.json)  <br>
     * - Custom preference files <br>
     * - Server configurations + SSL certificates <br>
     * - Notification rules <br>
     * - Command aliases <br>
     * - Custom themes <br>
     * - chatlogs.db (Room database, authoritative message store) <br>
     * <p>
     * HOT backup: IRC connections stay up and Room stays open. chatlogs.db is captured through
     * {@link MessageStorageRepository#snapshotTo(File)}, a consistent copy taken under a write lock;
     * messages committed after the snapshot are not part of the backup.
     * <p>
     * Must be called off the main thread.
     */
    public static void createBackup(Context context, File file, String password) throws IOException {
        try {
            // ------------------------------------------------------------
            // 1) Initialize ZIP file and compression/encryption parameters
            // ------------------------------------------------------------

            ZipFile zipFile = new ZipFile(file);
            ZipParameters params = new ZipParameters();
            params.setCompressionMethod(Zip4jConstants.COMP_DEFLATE);
            params.setCompressionLevel(Zip4jConstants.DEFLATE_LEVEL_NORMAL);
            if (password != null) {
                params.setEncryptFiles(true);
                params.setEncryptionMethod(Zip4jConstants.ENC_METHOD_AES);
                params.setAesKeyStrength(Zip4jConstants.AES_STRENGTH_256);
                params.setPassword(password);
            }
            // We provide streams manually for JSON content
            params.setSourceExternalStream(true);

            // ------------------------------------------------------------
            // 2) Backup SharedPreferences as JSON
            // ------------------------------------------------------------
            params.setFileNameInZip(BACKUP_PREFERENCES_PATH);
            zipFile.addStream(exportPreferencesToJson(context), params);

            // ------------------------------------------------------------
            // 3) Backup custom preference files
            //    (ListWithCustomSetting / SettingsHelper managed files)
            // ------------------------------------------------------------
            for (File f : SettingsHelper.getInstance(context).getCustomFiles()) {
                params.setFileNameInZip(BACKUP_PREF_VALUES_PREFIX + f.getName());
                zipFile.addFile(f, params);
            }

            // ------------------------------------------------------------
            // 4) Backup server configurations and SSL certificates
            // ------------------------------------------------------------
            ServerConfigManager configManager = ServerConfigManager.getInstance(context);
            StringWriter writer;

            for (ServerConfigData data : configManager.getServers()) {

                // 4a) Serialize server configuration as JSON
                writer = new StringWriter();
                SettingsHelper.getGson().toJson(data, writer);
                params.setFileNameInZip(BACKUP_SERVER_PREFIX + data.uuid + BACKUP_SERVER_SUFFIX);
                zipFile.addStream(new ByteArrayInputStream(writer.toString().getBytes()), params);

                // 4b) Backup server SSL certificate keystore (if present)
                File sslCertsFile = configManager.getServerSSLCertsFile(data.uuid);
                if (sslCertsFile.exists()) {
                    synchronized (ServerCertificateManager.get(sslCertsFile)) { // lock the helper to prevent any writes to the file
                        params.setFileNameInZip(BACKUP_SERVER_CERTS_PREFIX + data.uuid + BACKUP_SERVER_CERTS_SUFFIX);
                        zipFile.addFile(sslCertsFile, params);
                    }
                }
            }

            // ------------------------------------------------------------
            // 5) Backup notification rules
            // ------------------------------------------------------------
            writer = new StringWriter();
            NotificationRuleManager.saveUserRuleSettings(context, writer);
            params.setFileNameInZip(BACKUP_NOTIFICATION_RULES_PATH);
            zipFile.addStream(new ByteArrayInputStream(writer.toString().getBytes()), params);

            // ------------------------------------------------------------
            // 6) Backup command aliases
            // ------------------------------------------------------------
            writer = new StringWriter();
            CommandAliasManager.getInstance(context).saveUserSettings(writer);
            params.setFileNameInZip(BACKUP_COMMAND_ALIASES_PATH);
            zipFile.addStream(new ByteArrayInputStream(writer.toString().getBytes()), params);

            // ------------------------------------------------------------
            // 7) Backup custom themes
            // ------------------------------------------------------------
            ThemeManager themeManager = ThemeManager.getInstance(context);
            for (ThemeInfo themeInfo : themeManager.getCustomThemes()) {
                params.setFileNameInZip(BACKUP_THEME_PREFIX + themeInfo.uuid + BACKUP_THEME_SUFFIX);
                zipFile.addFile(themeManager.getThemePath(themeInfo.uuid), params);
            }

            // ------------------------------------------------------------
            // 8) Backup chatlogs.db (Room database)
            // ------------------------------------------------------------
            // This is the authoritative message store. A consistent snapshot is taken while Room
            // stays open; the snapshot holds chat logs in plain text, so it is always deleted.
            File chatlogSnapshot = new File(context.getCacheDir(), "chatlogs-backup.db");
            try {
                MessageStorageRepository.getInstance(context).snapshotTo(chatlogSnapshot);
                params.setFileNameInZip(CHATLOG_DB_PATH);
                zipFile.addFile(chatlogSnapshot, params);
            } finally {
                chatlogSnapshot.delete();
            }

        } catch (ZipException e) {
            throw new IOException("Invalid or incomplete backup", e);
        }
    }

    public static boolean verifyBackupFile(File file) {
        try {
            ZipFile zipFile = new ZipFile(file);
            FileHeader preferences = zipFile.getFileHeader(BACKUP_PREFERENCES_PATH);
            FileHeader notificationRules = zipFile.getFileHeader(BACKUP_NOTIFICATION_RULES_PATH);
            FileHeader commandAliases = zipFile.getFileHeader(BACKUP_COMMAND_ALIASES_PATH);
            return preferences != null && notificationRules != null && commandAliases != null;
        } catch (ZipException e) {
            e.printStackTrace();
            return false;
        }
    }

    public static boolean isBackupPasswordProtected(File file) {
        try {
            ZipFile zipFile = new ZipFile(file);
            FileHeader preferences = zipFile.getFileHeader(BACKUP_PREFERENCES_PATH);
            return preferences.isEncrypted();
        } catch (ZipException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Restores a full application backup from a ZIP archive.
     * <p>
     * Restore scope: <br>
     * - SharedPreferences <br>
     * - Custom preference files <br>
     * - Server configurations and SSL certificates <br>
     * - Notification rules <br>
     * - Command aliases <br>
     * - Custom themes <br>
     * - chatlogs.db (Room database, authoritative message store)
     * <p>
     * COLD restore: all IRC connections are disconnected and removed, and Room is closed and
     * blocked from reopening ({@link ChatLogDatabase#beginRestore()}) until the restore ends, so
     * nothing can open chatlogs.db while it is being replaced. Room reopens lazily afterwards.
     * <p>
     * The restore process is destructive and replaces current state.
     */
    public static void restoreBackup(Context context, File file, String password) throws IOException {
        try {

            quiesceForRestore(context);

            // ------------------------------------------------------------
            // 1) Open ZIP backup (optionally with password)
            // ------------------------------------------------------------
            ZipFile zipFile = new ZipFile(file);

            if (password != null)
                zipFile.setPassword(password);


            // ------------------------------------------------------------
            // 2) Restore SharedPreferences
            // ------------------------------------------------------------
            // Preferences are restored early as they may affect
            // subsequent restore logic and initialization behavior.
            Reader reader = new BufferedReader(new InputStreamReader(zipFile.getInputStream(
                    zipFile.getFileHeader(BACKUP_PREFERENCES_PATH))));
            importPreferencesFromJson(context, reader);
            reader.close();

            // ------------------------------------------------------------
            // 3) Reset server configuration
            // ------------------------------------------------------------
            ServerConfigManager.getInstance(context).deleteAllServers();

            // ------------------------------------------------------------
            // 4) Clear and reset custom themes directory
            // ------------------------------------------------------------
            ThemeManager themeManager = ThemeManager.getInstance(context);
            File themeDir = themeManager.getThemesDir();
            File[] themeDirFiles = themeDir.listFiles();
            if (themeDirFiles != null) {
                for (File f : themeDirFiles)
                    f.delete();
            }
            themeDir.mkdir();

            // ------------------------------------------------------------
            // 5) Iterate through ZIP entries and restore components
            // ------------------------------------------------------------
            for (Object header : zipFile.getFileHeaders()) {
                if (!(header instanceof FileHeader))
                    continue;
                FileHeader fileHeader = (FileHeader) header;

                // 5a) Restore server configurations
                if (fileHeader.getFileName().startsWith(BACKUP_SERVER_PREFIX) &&
                        fileHeader.getFileName().endsWith(BACKUP_SERVER_SUFFIX)) {
                    reader = new BufferedReader(new InputStreamReader(zipFile.getInputStream(
                            fileHeader)));
                    ServerConfigData data = SettingsHelper.getGson().fromJson(reader,
                            ServerConfigData.class);
                    data.migrateLegacyProperties();
                    reader.close();
                    ServerConfigManager.getInstance(context).saveServer(data);
                }

                // 5b) Restore server SSL certificate keystores
                if (fileHeader.getFileName().startsWith(BACKUP_SERVER_CERTS_PREFIX) &&
                        fileHeader.getFileName().endsWith(BACKUP_SERVER_CERTS_SUFFIX)) {
                    String uuid = fileHeader.getFileName();
                    uuid = uuid.substring(BACKUP_SERVER_CERTS_PREFIX.length(), uuid.length() -
                            BACKUP_SERVER_CERTS_SUFFIX.length());
                    ServerCertificateManager helper = ServerCertificateManager.get(context,
                            UUID.fromString(uuid));
                    try {
                        helper.loadKeyStore(zipFile.getInputStream(fileHeader));
                        helper.saveKeyStore();
                    } catch (GeneralSecurityException exception) {
                        throw new IOException(exception);
                    }
                }

                // 5c) Restore custom preference files
                if (fileHeader.getFileName().startsWith(BACKUP_PREF_VALUES_PREFIX)) {
                    String name = fileHeader.getFileName();
                    int iof = name.lastIndexOf('/');
                    if (iof != -1)
                        name = name.substring(iof + 1);
                    zipFile.extractFile(fileHeader,
                            ListWithCustomSetting.getCustomFilesDir(context).getAbsolutePath(),
                            null, name);
                }

                // 5d) Restore custom themes
                if (fileHeader.getFileName().startsWith(BACKUP_THEME_PREFIX) &&
                        fileHeader.getFileName().endsWith(BACKUP_THEME_SUFFIX)) {
                    String uuid = fileHeader.getFileName();
                    uuid = uuid.substring(BACKUP_THEME_PREFIX.length(), uuid.length() -
                            BACKUP_THEME_SUFFIX.length());
                    try {
                        File extractTo = themeManager.getThemePath(UUID.fromString(uuid));
                        zipFile.extractFile(fileHeader, extractTo.getParentFile().getAbsolutePath(),
                                null, extractTo.getName());
                    } catch (IllegalArgumentException e) {
                        Log.w("BackupManager", "Failed to restore theme " + uuid);
                    }
                }
            }

            // ------------------------------------------------------------
            // 6) Restore notification rules
            // ------------------------------------------------------------
            reader = new BufferedReader(new InputStreamReader(zipFile.getInputStream(
                    zipFile.getFileHeader(BACKUP_NOTIFICATION_RULES_PATH))));
            NotificationRuleManager.loadUserRuleSettings(reader);
            reader.close();
            NotificationRuleManager.saveUserRuleSettings(context);

            // ------------------------------------------------------------
            // 7) Restore command aliases
            // ------------------------------------------------------------
            reader = new BufferedReader(new InputStreamReader(zipFile.getInputStream(
                    zipFile.getFileHeader(BACKUP_COMMAND_ALIASES_PATH))));
            CommandAliasManager aliasManager = CommandAliasManager.getInstance(context);
            aliasManager.loadUserSettings(reader);
            reader.close();
            aliasManager.saveUserSettings();

            // ------------------------------------------------------------
            // 8) Reload themes after restore
            // ------------------------------------------------------------
            themeManager.reloadThemes();

            // ------------------------------------------------------------
            // 9) Restore chatlogs.db (Room database)
            // ------------------------------------------------------------
            // chatlogs.db is the authoritative message store and replaces all previous message
            // data. Room is closed and cannot reopen until endRestore() (see quiesceForRestore).
            // Backups made before chat logs existed have no chatlogs.db: the current logs are kept.
            if (zipFile.getFileHeader(CHATLOG_DB_PATH) != null) {
                File chatlogDb = context.getDatabasePath(CHATLOG_DB_PATH);

                // Deletes chatlogs.db together with its -wal, -shm and -journal files: a stale
                // -wal left by the old database would otherwise be applied to the restored one.
                SQLiteDatabase.deleteDatabase(chatlogDb);

                zipFile.extractFile(
                        CHATLOG_DB_PATH,
                        chatlogDb.getParentFile().getAbsolutePath(),
                        null,
                        chatlogDb.getName()
                );
            }

        } catch (ZipException e) {
            throw new IOException("Invalid or incomplete backup", e);
        } finally {
            // Room reopens lazily on the next access, on the restored file
            ChatLogDatabase.endRestore();
        }
    }

    private static void quiesceForRestore(Context context) {
        ServerConnectionManager scm =
                ServerConnectionManager.getInstance(context);

        if (scm != null) {
            // Destructive shutdown: the restore replaces all servers
            scm.disconnectAndRemoveAllConnections(true);
        }

        // After all message producers are stopped, close Room and keep it closed
        // until restoreBackup() calls endRestore()
        ChatLogDatabase.beginRestore();
    }

    private static InputStream exportPreferencesToJson(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String str = SettingsHelper.getGson().toJson(prefs.getAll());
        return new ByteArrayInputStream(str.getBytes());
    }

    @SuppressLint("ApplySharedPref")
    private static void importPreferencesFromJson(Context context, Reader reader) {
        SharedPreferences.Editor prefs = PreferenceManager.getDefaultSharedPreferences(context).edit();
        SettingsHelper.getInstance(context).clear();
        JsonObject obj = SettingsHelper.getGson().fromJson(reader, JsonObject.class);
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            JsonElement el = entry.getValue();
            if (el.isJsonArray()) {
                Set<String> items = new HashSet<>();
                for (JsonElement child : el.getAsJsonArray())
                    items.add(child.getAsString());
                prefs.putStringSet(entry.getKey(), items);
            } else {
                JsonPrimitive primitive = el.getAsJsonPrimitive();
                if (primitive.isBoolean()) {
                    prefs.putBoolean(entry.getKey(), primitive.getAsBoolean());
                } else if (primitive.isNumber()) {
                    Number number = primitive.getAsNumber();
                    if (number instanceof Float || number instanceof Double)
                        prefs.putFloat(entry.getKey(), number.floatValue());
                    else if (number instanceof Long)
                        prefs.putLong(entry.getKey(), number.longValue());
                    else
                        prefs.putInt(entry.getKey(), number.intValue());
                } else if (primitive.isString()) {
                    prefs.putString(entry.getKey(), primitive.getAsString());
                }
            }
        }
        prefs.commit(); // This will be called asynchronously
    }

}
