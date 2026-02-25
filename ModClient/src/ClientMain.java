import com.google.gson.Gson;
import com.google.gson.JsonObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.swing.*;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;
import java.util.Comparator;
import java.util.Scanner;
import java.util.stream.Stream;
import java.util.zip.*;

public class ClientMain {

    //USER-SPECIFIC CONFIGURATION
    private static final HttpClient Client = HttpClient.newHttpClient();
    private static final String SECURITY_KEY = "";   //Hardcoded per user
    private static final String CONFIG_FILE = "client-config.json";
    private static final String USER = "";//Hardcoded per user
    private static final String SERVER_URL = "";//Use of either personal domain suggested
    private static final String WORLD_FOLDER_NAME = ""; //Fill with the world name
    private static Path workingDirectory;

    private static final Gson gson = new Gson();

    public static void main(String[] args) throws Exception {
        try {
            Scanner scanner = new Scanner(System.in);

            loadOrCreateConfig();

            //Main menu loop
            while (true) {
                System.out.println("\n--- Sync Menu ---");
                System.out.println("1. Download folder from server");
                System.out.println("2. Upload folder to server");
                System.out.println("3. Change settings");
                System.out.println("4. Exit");
                System.out.print("Choose an option: ");

                String choice = scanner.nextLine().trim();

                switch (choice) {
                    case "1":
                        downloadFolder();
                        break;
                    case "2":
                        uploadFolder();
                        break;
                    case "3":
                        changeSettings();
                        break;
                    case "4":
                        System.out.println("Goodbye.");
                        Thread.sleep(3000);
                        return;
                    default:
                        System.out.println("Invalid choice.");
                }
            }
        } catch (Exception e) {
            System.out.println("Unexpected error occurred during processing. Closing..");
            Thread.sleep(3000);
            System.exit(-1);
        }
    }

    //CONFIG HANDLING
    private static void loadOrCreateConfig() {
        Path configPath = Paths.get(CONFIG_FILE);

        if (Files.exists(configPath)) {
            try {
                JsonObject config = gson.fromJson(Files.readString(configPath), JsonObject.class);
                workingDirectory = Path.of(config.get("workingDirectory").getAsString());
                return;
            } catch (Exception e) {
                System.out.println("Config file corrupted. Recreating...");
            }
        }

        // First launch setup
        System.out.println("First-time setup:");

        workingDirectory = pickFolderWithGUI();
        System.out.println("Selected working directory: " + workingDirectory);

        saveConfig();
    }

    private static void changeSettings() {
        System.out.println("\n--- Change Settings ---");

        System.out.println("Pick a new working directory...");
        workingDirectory = pickFolderWithGUI();

        saveConfig();
        System.out.println("Settings updated.");
    }

    private static void saveConfig() {
        try {
            JsonObject config = new JsonObject();
            config.addProperty("workingDirectory", workingDirectory.toString());

            Files.writeString(Paths.get(CONFIG_FILE), gson.toJson(config));
        } catch (IOException e) {
            System.out.println("Failed to save config: " + e.getMessage());
        }
    }

    private static Path pickFolderWithGUI() {
        try {
            //To make it not look like the year is 2005
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            //Falls back onto default look if this fails
        }

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select your sync folder");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);

        int result = chooser.showOpenDialog(null);
        if (result == JFileChooser.APPROVE_OPTION) {
            return chooser.getSelectedFile().toPath();
        }

        System.out.println("No folder selected. Exiting.");
        System.exit(0);
        return null;
    }

    //AUTH SIGNATURE
    private static String hmacSha256(long timestamp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec =
                    new SecretKeySpec(SECURITY_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] rawHmac = mac.doFinal(("auth:" + timestamp).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(rawHmac);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute HMAC", e);
        }
    }

    //DOWNLOAD (GET FOLDER)
    public static void downloadFolder() throws Exception {
        System.out.println("Downloading world...");
        long timestamp = System.currentTimeMillis() / 1000;
        String signature = hmacSha256(timestamp);

        JsonObject req = new JsonObject();
        req.addProperty("user", USER);
        req.addProperty("signature", signature);
        req.addProperty("timestamp", timestamp);

        byte[] responseBytes = sendPost("/getFolder", req.toString().getBytes(StandardCharsets.UTF_8));

        if (responseBytes.length < 4 || responseBytes[0] != 0x50 || responseBytes[1] != 0x4B) {
            throw new RuntimeException("Server returned error: " +
                    new String(responseBytes, StandardCharsets.UTF_8));
        }

        Path tempZip = workingDirectory.resolveSibling("temp_download.zip");
        try {
            Files.write(tempZip, responseBytes);
            unzip(tempZip.toFile(), workingDirectory.toFile());
            System.out.println("Download complete.");
        } finally {
            Files.deleteIfExists(tempZip);
        }
    }


    //UPLOAD (PUT FOLDER)
    public static void uploadFolder() throws Exception {
        System.out.println("Uploading folder...");
        long timestamp = System.currentTimeMillis() / 1000;
        String signature = hmacSha256(timestamp);

        Path worldFolder = workingDirectory.resolve(WORLD_FOLDER_NAME);
        Path tempZip = workingDirectory.resolveSibling("temp_upload.zip");

        try {
            zipFolder(worldFolder.toFile(), tempZip.toFile());
            byte[] zipBytes = Files.readAllBytes(tempZip);
            sendPostRaw("/putFolder", zipBytes, USER, signature, timestamp);
            deleteDirectoryContents(worldFolder);
            Files.deleteIfExists(worldFolder);
            System.out.println("Upload complete.");
        } finally {
            Files.deleteIfExists(tempZip);
        }
    }

    //ZIP UTILITIES
    public static void zipFolder(File sourceFolder, File zipFile) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            Path sourcePath = sourceFolder.toPath();

            Files.walk(sourcePath).forEach(path -> {
                try {
                    if (Files.isDirectory(path)) return;

                    ZipEntry entry = new ZipEntry(sourcePath.relativize(path).toString());
                    zos.putNextEntry(entry);
                    Files.copy(path, zos);
                    zos.closeEntry();

                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    public static void unzip(File zipFile, File destDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File newFile = new File(destDir, entry.getName());

                String destDirPath = destDir.getCanonicalPath();
                String newFilePath = newFile.getCanonicalPath();
                if (!newFilePath.startsWith(destDirPath + File.separator)) {
                    throw new IOException("Zip slip detected: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    newFile.mkdirs();
                } else {
                    newFile.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(newFile)) {
                        zis.transferTo(fos);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    public static void deleteDirectoryContents(Path dir) throws IOException {
        if (!Files.exists(dir)) return;

        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .filter(p -> !p.equals(dir))
                    .forEach(p -> {
                        try { Files.delete(p); }
                        catch (IOException e) { e.printStackTrace(); }
                    });
        }
    }

    //HTTP Helpers :3
    public static byte[] sendPost(String endpoint, byte[] body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(SERVER_URL + endpoint))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<byte[]> response = Client.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Server error: " + response.statusCode());
        }

        return response.body();
    }

    public static byte[] sendPostRaw(String endpoint, byte[] body, String user, String signature, long timestamp) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(SERVER_URL + endpoint))
                .header("Content-Type", "application/octet-stream")
                .header("X-User", user)
                .header("X-Signature", signature)
                .header("X-Timestamp", String.valueOf(timestamp))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<byte[]> response = Client.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Server error: " + response.statusCode());
        }

        return response.body();
    }
}
