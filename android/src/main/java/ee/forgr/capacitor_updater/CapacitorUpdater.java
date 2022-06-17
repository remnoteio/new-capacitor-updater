package ee.forgr.capacitor_updater;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import com.android.volley.AuthFailureError;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.ArrayList;
import android.provider.Settings.Secure;

interface Callback {
    void callback(JSONObject jsonObject);
}

public class CapacitorUpdater {
    static final String AB = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    static SecureRandom rnd = new SecureRandom();
    private final String TAG = "Capacitor-updater";
    private final Context context;
    private final String basePathHot = "versions";
    private final SharedPreferences prefs;
    private final SharedPreferences.Editor editor;
    private String versionBuild = "";
    private String versionCode = "";
    private String versionOs = "";

    public String appId = "";
    public String deviceID = "";
    public final String pluginVersion = "3.3.11";
    public String statsUrl = "";

    public CapacitorUpdater (final Context context) throws PackageManager.NameNotFoundException {
        this.context = context;
        this.prefs = this.context.getSharedPreferences("CapWebViewSettings", Activity.MODE_PRIVATE);
        this.editor = this.prefs.edit();
        this.versionOs = Build.VERSION.RELEASE;
        this.deviceID = Secure.getString(context.getContentResolver(), Secure.ANDROID_ID);
        final PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        this.versionBuild = pInfo.versionName;
        this.versionCode = Integer.toString(pInfo.versionCode);
    }

    private final FilenameFilter filter = new FilenameFilter() {
        @Override
        public boolean accept(final File f, final String name) {
            // ignore directories generated by mac os x
            return !name.startsWith("__MACOSX") && !name.startsWith(".") && !name.startsWith(".DS_Store");
        }
    };

    private int calcTotalPercent(final int percent, final int min, final int max) {
        return (percent * (max - min)) / 100 + min;
    }

    void notifyDownload(final int percent) {
        return;
    }

    private String randomString(final int len){
        final StringBuilder sb = new StringBuilder(len);
        for(int i = 0; i < len; i++)
            sb.append(AB.charAt(rnd.nextInt(AB.length())));
        return sb.toString();
    }
    private File unzip(final File zipFile, final String dest) throws IOException {
        final File targetDirectory = new File(this.context.getFilesDir()  + "/" + dest);
        final ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)));
        try {
            int count;
            final int bufferSize = 8192;
            final byte[] buffer = new byte[bufferSize];
            final long lengthTotal = zipFile.length();
            long lengthRead = bufferSize;
            int percent = 0;
            this.notifyDownload(75);

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                final File file = new File(targetDirectory, entry.getName());
                final String canonicalPath = file.getCanonicalPath();
                final String canonicalDir = (new File(String.valueOf(targetDirectory))).getCanonicalPath();
                final File dir = entry.isDirectory() ? file : file.getParentFile();

                if (!canonicalPath.startsWith(canonicalDir)) {
                    throw new FileNotFoundException("SecurityException, Failed to ensure directory is the start path : " +
                            canonicalDir + " of " + canonicalPath);
                }

                if (!dir.isDirectory() && !dir.mkdirs()) {
                    throw new FileNotFoundException("Failed to ensure directory: " +
                            dir.getAbsolutePath());
                }

                if (entry.isDirectory()) {
                    continue;
                }

                try(final FileOutputStream outputStream = new FileOutputStream(file)) {
                    while ((count = zis.read(buffer)) != -1)
                        outputStream.write(buffer, 0, count);
                }

                final int newPercent = (int)((lengthRead * 100) / lengthTotal);
                if (lengthTotal > 1 && newPercent != percent) {
                    percent = newPercent;
                    this.notifyDownload(this.calcTotalPercent(percent, 75, 90));
                }

                lengthRead += entry.getCompressedSize();
            }
            return targetDirectory;
        } finally {
            try {
                zis.close();
            } catch (final IOException e) {
                Log.e(this.TAG, "Failed to close zip input stream", e);
            }
        }
    }

    private void flattenAssets(final File sourceFile, final String dest) throws IOException {
        if (!sourceFile.exists()) {
            throw new FileNotFoundException("Source file not found: " + sourceFile.getPath());
        }
        final File destinationFile = new File(this.context.getFilesDir()  + "/" + dest);
        destinationFile.getParentFile().mkdirs();
        final String[] entries = sourceFile.list(this.filter);
        if (entries == null || entries.length == 0) {
            throw new IOException("Source file was not a directory or was empty: " + sourceFile.getPath());
        }
        if (entries.length == 1 && !entries[0].equals("index.html")) {
            final File child = new File(sourceFile.getPath() + "/" + entries[0]);
            child.renameTo(destinationFile);
        } else {
            sourceFile.renameTo(destinationFile);
        }
        sourceFile.delete();
    }

    private File downloadFile(final String url, final String dest) throws IOException {

        final URL u = new URL(url);
        final URLConnection connection = u.openConnection();
        final InputStream is = u.openStream();
        final DataInputStream dis = new DataInputStream(is);

        final File target = new File(this.context.getFilesDir()  + "/" + dest);
        target.getParentFile().mkdirs();
        target.createNewFile();
        final FileOutputStream fos = new FileOutputStream(target);

        final long totalLength = connection.getContentLength();
        final int bufferSize = 1024;
        final byte[] buffer = new byte[bufferSize];
        int length;

        int bytesRead = bufferSize;
        int percent = 0;
        this.notifyDownload(10);
        while ((length = dis.read(buffer))>0) {
            fos.write(buffer, 0, length);
            final int newPercent = (int)((bytesRead * 100) / totalLength);
            if (totalLength > 1 && newPercent != percent) {
                percent = newPercent;
                this.notifyDownload(this.calcTotalPercent(percent, 10, 70));
            }
            bytesRead += length;
        }
        return target;
    }

    private void deleteDirectory(final File file) throws IOException {
        if (file.isDirectory()) {
            final File[] entries = file.listFiles();
            if (entries != null) {
                for (final File entry : entries) {
                    this.deleteDirectory(entry);
                }
            }
        }
        if (!file.delete()) {
            throw new IOException("Failed to delete " + file);
        }
    }

    public String download(final String url) throws IOException {
        this.notifyDownload(0);
        final String path = this.randomString(10);
        final File zipFile = new File(this.context.getFilesDir()  + "/" + path);
        final String folderNameUnZip = this.randomString(10);
        final String version = this.randomString(10);
        final String folderName = this.basePathHot + "/" + version;
        this.notifyDownload(5);
        final File downloaded = this.downloadFile(url, path);
        this.notifyDownload(71);
        final File unzipped = this.unzip(downloaded, folderNameUnZip);
        zipFile.delete();
        this.notifyDownload(91);
        this.flattenAssets(unzipped, folderName);
        this.notifyDownload(100);
        return version;
    }

    public ArrayList<String> list() {
        final ArrayList<String> res = new ArrayList<String>();
        final File destHot = new File(this.context.getFilesDir()  + "/" + this.basePathHot);
        Log.i(this.TAG, "list File : " + destHot.getPath());
        if (destHot.exists()) {
            for (final File i : destHot.listFiles()) {
                res.add(i.getName());
            }
        } else {
            Log.i(this.TAG, "No version available" + destHot);
        }
        return res;
    }

    public Boolean delete(final String version, final String versionName) throws IOException {
        final File destHot = new File(this.context.getFilesDir()  + "/" + this.basePathHot + "/" + version);
        if (destHot.exists()) {
            this.deleteDirectory(destHot);
            return true;
        }
        Log.i(this.TAG, "Directory not removed: " + destHot.getPath());
        this.sendStats("delete", versionName);
        return false;
    }

    public Boolean set(final String version, final String versionName) {
        final File destHot = new File(this.context.getFilesDir()  + "/" + this.basePathHot + "/" + version);
        final File destIndex = new File(destHot.getPath()  + "/index.html");
        if (destHot.exists() && destIndex.exists()) {
            this.editor.putString("lastPathHot", destHot.getPath());
            this.editor.putString("serverBasePath", destHot.getPath());
            this.editor.putString("versionName", versionName);
            this.editor.commit();
            this.sendStats("set", versionName);
            return true;
        }
        this.sendStats("set_fail", versionName);
        return false;
    }

    public void getLatest(final String url, final Callback callback) {
        final String deviceID = this.getDeviceID();
        final String appId = this.getAppId();
        final String versionBuild = this.versionBuild;
        final String versionCode = this.versionCode;
        final String versionOs = this.versionOs;
        final String pluginVersion = this.pluginVersion;
        final String versionName = this.getVersionName().equals("") ? "builtin" : this.getVersionName();
        final StringRequest stringRequest = new StringRequest(Request.Method.GET, url,
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(final String response) {
                        try {
                            final JSONObject jsonObject = new JSONObject(response);
                            callback.callback(jsonObject);
                        } catch (final JSONException e) {
                            Log.e(CapacitorUpdater.this.TAG, "Error parsing JSON", e);
                        }
                    }
                }, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(final VolleyError error) {
                Log.e(CapacitorUpdater.this.TAG, "Error getting Latest" +  error);
            }
        }) {
            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {
                final Map<String, String>  params = new HashMap<String, String>();
                params.put("cap_platform", "android");
                params.put("cap_device_id", deviceID);
                params.put("cap_app_id", appId);
                params.put("cap_version_build", versionBuild);
                params.put("cap_version_code", versionCode);
                params.put("cap_version_os", versionOs);
                params.put("cap_version_name", versionName);
                params.put("cap_plugin_version", pluginVersion);
                return params;
            }
        };
        final RequestQueue requestQueue = Volley.newRequestQueue(this.context);
        requestQueue.add(stringRequest);
    }

    public String getLastPathHot() {
        return this.prefs.getString("lastPathHot", "public");
    }

    public String getVersionName() {
        return this.prefs.getString("versionName", "");
    }

    public void reset() {
        final String version = this.prefs.getString("versionName", "");
        this.sendStats("reset", version);
        this.editor.putString("lastPathHot", "public");
        this.editor.putString("serverBasePath", "public");
        this.editor.putString("versionName", "");
        this.editor.commit();
    }

    public void sendStats(final String action, final String version) {
        if (this.getStatsUrl() == "") { return; }
        final URL url;
        final JSONObject json = new JSONObject();
        final String jsonString;
        try {
            url = new URL(this.getStatsUrl());
            json.put("platform", "android");
            json.put("action", action);
            json.put("version_name", version);
            json.put("device_id", this.getDeviceID());
            json.put("version_build", this.versionBuild);
            json.put("version_code", this.versionCode);
            json.put("version_os", this.versionOs);
            json.put("plugin_version", this.pluginVersion);
            json.put("app_id", this.getAppId());
            jsonString = json.toString();
        } catch (final Exception ex) {
            Log.e(this.TAG, "Error get stats", ex);
            return;
        }
        new Thread(new Runnable(){
            @Override
            public void run() {
                HttpURLConnection con = null;
                try {
                    con = (HttpURLConnection) url.openConnection();
                    con.setRequestMethod("POST");
                    con.setRequestProperty("Content-Type", "application/json");
                    con.setRequestProperty("Accept", "application/json");
                    con.setRequestProperty("Content-Length", Integer.toString(jsonString.getBytes().length));
                    con.setDoOutput(true);
                    con.setConnectTimeout(500);
                    final DataOutputStream wr = new DataOutputStream (con.getOutputStream());
                    wr.writeBytes(jsonString);
                    wr.close();
                    final int responseCode = con.getResponseCode();
                    if (responseCode != 200) {
                        Log.e(CapacitorUpdater.this.TAG, "Stats error responseCode: " + responseCode);
                    } else {
                        Log.i(CapacitorUpdater.this.TAG, "Stats send for \"" + action + "\", version " + version);
                    }
                } catch (final Exception ex) {
                    Log.e(CapacitorUpdater.this.TAG, "Error post stats", ex);
                } finally {
                    if (con != null) {
                        con.disconnect();
                    }
                }
            }
        }).start();
    }

    public String getStatsUrl() {
        return this.statsUrl;
    }

    public void setStatsUrl(final String statsUrl) {
        this.statsUrl = statsUrl;
    }

    public String getAppId() {
        return this.appId;
    }

    public void setAppId(final String appId) {
        this.appId = appId;
    }

    public String getDeviceID() {
        return this.deviceID;
    }

    public void setDeviceID(final String deviceID) {
        this.deviceID = deviceID;
    }
}
