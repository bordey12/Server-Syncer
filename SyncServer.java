import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import com.google.gson.JsonObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;


public class SyncServer {

    //PASSWORD MAP
    private static final Map<String, String> PassMap = Map.of(
            //Fill with custom usernames and Security Keys
    );

    private static final Path FOLDER_PATH = Path.of("");
    //Fill with the path of the parent folder to the world
    //Is directory independent. Feel free to use parent directory.

    private static String checkedOutBy = null;

    private static String hmacSha256(String key, long timestamp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec =
                    new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] rawHmac = mac.doFinal(("auth:" + timestamp).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(rawHmac);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute HMAC", e);
        }
    }

    private static boolean verifySignature(String user, String signature, long timestamp) {
        long now = System.currentTimeMillis() / 1000;
        if (Math.abs(now - timestamp) > 120) return false;

        String key = PassMap.get(user);
        if (key == null) return false;

        String expected = hmacSha256(key, timestamp);
        return expected.equals(signature);
    }

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

                // Zip slip protection
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

    public static void main(String[] args) throws Exception {
        //Create the server
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

        server.createContext("/getFolder", new GetFolderHandler());
        server.createContext("/putFolder", new PutFolderHandler());

        server.setExecutor(null);
        server.start();

        System.out.println("Server running on http://localhost:8080");
    }

    static class GetFolderHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (checkedOutBy != null) {
                sendError(exchange, "World is currently checked out by " + checkedOutBy);
                return;
            }
            JsonObject req = parseJson(exchange);

            if(req == null){
                sendError(exchange, "Invalid request.");
                return;
            }

            String user = req.get("user").getAsString();
            String signature = req.get("signature").getAsString();
            long timestamp = req.get("timestamp").getAsLong();

            if (!verifySignature(user, signature, timestamp)) {
                sendError(exchange, "Invalid signature");
                return;
            }

            Path tempZip = FOLDER_PATH.resolveSibling("temp_server_get.zip");
            try {
                zipFolder(FOLDER_PATH.toFile(), tempZip.toFile());

                byte[] zipBytes = Files.readAllBytes(tempZip);
                checkedOutBy = user;
                sendBytes(exchange, zipBytes);

                System.out.println("[" + user + "] GET completed.");
            } catch (Exception e) {
                e.printStackTrace();
                sendError(exchange, "Server error");
                checkedOutBy = null;
            } finally {
                try {
                    Files.deleteIfExists(tempZip);
                } catch (IOException ignored) {}
            }
        }
    }

    static class PutFolderHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String user = exchange.getRequestHeaders().getFirst("X-User");
            String signature = exchange.getRequestHeaders().getFirst("X-Signature");
            String timestampStr = exchange.getRequestHeaders().getFirst("X-Timestamp");

            if (user == null || signature == null || timestampStr == null) {
                sendError(exchange, "Missing headers");
                return;
            }

            if (!user.equals(checkedOutBy)) {
                sendError(exchange, "World is not checked out by you");
                return;
            }

            long timestamp = Long.parseLong(timestampStr);

            if (!verifySignature(user, signature, timestamp)) {
                sendError(exchange, "Invalid signature");
                return;
            }

            Path tempZip = FOLDER_PATH.resolveSibling("temp_server_put.zip");
            Path tempUnzipDir = FOLDER_PATH.resolveSibling(FOLDER_PATH.getFileName() + "_tmp_unzip");
            try {
                byte[] zipBytes = exchange.getRequestBody().readAllBytes();

                if (zipBytes.length < 4 || zipBytes[0] != 0x50 || zipBytes[1] != 0x4B) {
                    sendError(exchange, "Invalid zip data");
                    return;
                }

                Files.write(tempZip, zipBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

                deleteDirectoryContents(tempUnzipDir);
                Files.createDirectories(tempUnzipDir);

                unzip(tempZip.toFile(), tempUnzipDir.toFile());

                deleteDirectoryContents(FOLDER_PATH);
                moveDirectoryContents(tempUnzipDir, FOLDER_PATH);

                checkedOutBy = null;
                sendOk(exchange);
                System.out.println("[" + user + "] PUT completed.");
            } catch (Exception e) {
                e.printStackTrace();
                sendError(exchange, "Server error");
            } finally {
                try {
                    Files.deleteIfExists(tempZip);
                } catch (IOException ignored) {}
                try {
                    deleteDirectoryContents(tempUnzipDir);
                    Files.deleteIfExists(tempUnzipDir);
                } catch (IOException ignored) {}
            }
        }
    }

    private static JsonObject parseJson(HttpExchange exchange) throws IOException {
        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    private static void sendError(HttpExchange exchange, String message) throws IOException {
        JsonObject obj = new JsonObject();
        obj.addProperty("status", "error");
        obj.addProperty("message", message);

        byte[] bytes = obj.toString().getBytes(StandardCharsets.UTF_8);

        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void sendOk(HttpExchange exchange) throws IOException {
        JsonObject obj = new JsonObject();
        obj.addProperty("status", "ok");

        byte[] bytes = obj.toString().getBytes(StandardCharsets.UTF_8);

        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void sendBytes(HttpExchange exchange, byte[] data) throws IOException {
        exchange.sendResponseHeaders(200, data.length);
        exchange.getResponseBody().write(data);
        exchange.close();
    }

    public static void moveDirectoryContents(Path srcDir, Path destDir) throws IOException {
        if (!Files.exists(srcDir)) return;
        Files.createDirectories(destDir);
        try (Stream<Path> stream = Files.walk(srcDir)) {
            stream.filter(p -> !Files.isDirectory(p)).forEach(p -> {
                Path relative = srcDir.relativize(p);
                Path target = destDir.resolve(relative);
                try {
                    Files.createDirectories(target.getParent());
                    Files.move(p, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

}
