# TgMon - Real-Time Server & Container Monitoring System

TgMon is a full-stack telemetry and container monitoring solution comprising:
1. **Python FastAPI Backend**: Collects host hardware metrics (CPU, RAM, Disks, Network) and Docker container statuses, exposing REST endpoints and real-time WebSockets.
2. **Android Native Client**: Built with Kotlin and Jetpack Compose, featuring Material 3 UI, real-time charts, container lifecycle controls, and desktop widgets via Glance.

---

## Architecture Overview

`
┌─────────────────────────────────┐        WebSocket / REST API        ┌──────────────────────────────────┐
│      TgMon Android Client       │ ◄────────────────────────────────► │       TgMon Python Server        │
│  - Jetpack Compose (Material 3) │   http://10.0.2.2:8000 (Emulator)  │  - FastAPI + Uvicorn             │
│  - Coroutines & Flow            │   http://<LAN_IP>:8000 (Device)    │  - psutil system metrics         │
│  - Room Local Cache             │                                    │  - Docker SDK integration        │
│  - Glance Home Screen Widget    │   Header:                          │  - SQLite historical metrics     │
└─────────────────────────────────┘   Authorization: Bearer <TOKEN>    └──────────────────────────────────┘
`

---

## 1. Server Setup (Backend)

### Prerequisites
- Python 3.10 or higher
- Optional: Docker Engine running locally (if container monitoring is desired)

### Installation & Run

1. Navigate to the server/ directory:
   `ash
   cd server
   `

2. Create and activate a Python virtual environment:
   - **Linux / macOS**:
     `ash
     python3 -m venv venv
     source venv/bin/activate
     `
   - **Windows**:
     `cmd
     python -m venv venv
     venv\Scripts\activate
     `

3. Install required packages:
   `ash
   pip install -r requirements.txt
   `

4. Configure the environment:
   A ready-to-test .env.api is already included:
   `env
   TGMON_TOKEN=student_secure_token_123
   `
   *(To customize, copy .env.example to .env.api and change the token).*

5. Start the server:
   `ash
   uvicorn main:app --host 0.0.0.0 --port 8000
   `

6. Verify server is running:
   `ash
   curl -H "Authorization: Bearer student_secure_token_123" http://localhost:8000/health
   `

---

## 2. Android App Setup (Client)

### Prerequisites
- Android Studio (Koala / Ladybug or newer recommended)
- Android SDK (API Level 26 minimum, API 35 target)

### Running the App

1. Open the project folder in **Android Studio**.
2. Let Gradle sync project dependencies.
3. Select an emulator or connected physical Android device.
4. Run the **pp** configuration in **debug** mode.

### Default Connection Behavior
- **Android Emulator**: Automatically pre-configured to connect to http://10.0.2.2:8000 (which routes to localhost:8000 on the computer running the server) with pre-seeded token student_secure_token_123.
- **Physical Device**: Go to Settings / Servers inside the app and enter your computer's local Wi-Fi IP address (e.g. http://192.168.1.50:8000).

---

## 3. Key Endpoints

| Endpoint | Method | Description |
| :--- | :---: | :--- |
| /health | GET | Instant snapshot of CPU, RAM, Disk, and system uptime. |
| /metrics/history | GET | Historical telemetry records stored in SQLite database. |
| /docker/containers | GET | List of Docker containers with CPU/RAM usage and status. |
| /docker/container/{id}/{action} | POST | Container actions: restart, start, stop, pause, unpause. |
| /docker/container/{id}/logs | GET | Container stdout/stderr log stream. |
| /ws/telemetry | WebSocket | Real-time bi-directional telemetry and event streaming. |

---

## 4. Security & Configuration Notes

- **Secrets**: Passwords, release signing keys, and private IPs have been sanitized from this submission.
- **Release Signing**: If building a signed release APK, copy keystore.properties.example to keystore.properties and fill in your keystore parameters. Standard debug builds do not require a keystore.
