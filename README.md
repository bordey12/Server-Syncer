# Server Syncer

A client-server tool for syncronizing a Minecraft world folder between a localhost server and multiple users. The server runs on a local machine and is exposed via a Cloudflare Tunnel. Users receive a custom client executable with their credentials hardcoded, allowing them to download and upload the world folder on demand.

## How It Works

The server hosts a Minecraft world folder and exposes two endpoints — `/getFolder` for downloading and `/putFolder` for uploading. Each user is assigned a unique security key which is hardcoded into their personal client executable. Requests are authenticated using HMAC-SHA256 with timestamp validation to prevent replay attacks.

- **Download** — The client requests the world folder, receives it as a zip, and extracts it into the user's Minecraft saves directory, or whatever directory was chosen.
- **Upload** — The client zips the world folder, sends it to the server, and deletes their local copy once the upload is confirmed.

## Server Setup

1. Populate `PassMap` in `SyncServer.java` with usernames and their corresponding security keys
2. Set `FOLDER_PATH` to the parent directory containing the world folder
3. Build and run `SyncServer.java`
4. Configure a Cloudflare Tunnel to route traffic to `localhost:8080`

## Client Setup

1. Set `SECURITY_KEY` in `ClientMain.java` to the user's assigned key
2. Set `USER` to the user's assigned username
3. Set `SERVER_URL` to the Cloudflare domain
4. Set `WORLD_FOLDER_NAME` to the exact name of the Minecraft world folder
5. Build and bundle into an executable using Launch4J

## Dependencies

- [Gson](https://github.com/google/gson) — included in the `lib` folder

## Notes

- Only one user should hold the world at a time. Upload before another user downloads.
- The client will delete the local world folder after a successful upload.
- Java 11 or higher is required.
