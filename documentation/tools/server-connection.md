# Communication Settings

> Menu: Extensions > QP Scope > Communication Settings...
> [Back to README](../../README.md) | [All Tools](../UTILITIES.md)

## Purpose

Configure and test the connection between QuPath and the microscope control server, and manage notification alerts for workflow events. The Python microscope server handles communication with Micro-Manager and the physical microscope hardware. This tool lets you set connection parameters, test connectivity, monitor connection health, and configure push notifications.

## Prerequisites

- Python microscope server installed and running (or ready to start)
- Network access between QuPath and the server (localhost for local, or appropriate firewall rules for remote)
- For push notifications: [ntfy app](https://ntfy.sh) on your phone (optional)

## Options

### Connection Tab

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| Host | TextField | 127.0.0.1 | IP address or hostname of the microscope server |
| Port | Spinner | 5000 | Server port number |
| Auto-connect | CheckBox | ON | Automatically connect when QuPath starts |
| Auto-fallback | CheckBox | ON | Fall back to CLI mode if socket connection fails |

### Connection Buttons

| Button | Description |
|--------|-------------|
| Test Connection | Verify communication with the server. Returns stage position if successful. |
| Connect Now | Establish the connection immediately |

### Advanced Tab

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| Connection timeout (ms) | Spinner | 5000 | Time to wait for initial connection |
| Read timeout (ms) | Spinner | 30000 | Time to wait for server responses |
| Max reconnect attempts | Spinner | 3 | Number of reconnection attempts on failure |
| Reconnect delay (ms) | Spinner | 1000 | Delay between reconnection attempts |
| Health check interval (ms) | Spinner | 30000 | How often to verify the server is alive |

### Alerts Tab

Configure local and remote notification alerts for workflow events.

**Local Alerts:**

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| Completion beep | CheckBox | ON | Play a system beep when workflows complete |

**Push Notifications (ntfy.sh):**

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| Enable push notifications | CheckBox | OFF | Send notifications via ntfy.sh |
| Topic | TextField | (empty) | Your ntfy.sh topic name (must match your phone subscription) |
| Server | TextField | https://ntfy.sh | ntfy.sh server URL (default public server) |
| Test Notification | Button | - | Send a test notification to verify your setup |

**Event Toggles:**

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| Acquisition complete | CheckBox | ON | Notify when acquisitions finish |
| Stitching complete | CheckBox | ON | Notify when stitching finishes |
| Errors | CheckBox | ON | Notify when errors occur |

### Status Tab

| Feature | Description |
|---------|-------------|
| Connection Status | Current state of the connection |
| Connection Log | Timestamped log of connection events |
| Clear Log | Button to reset the log display |

## Workflow

### Server Connection

1. Open Communication Settings from the menu
2. Verify the Host and Port match your microscope server configuration
3. Click **Test Connection** to verify communication
4. If successful, the test returns the current stage position
5. Click **Connect Now** to establish the connection (or enable Auto-connect for future sessions)
6. Monitor connection health in the Status tab

### Setting Up Push Notifications

1. Install the [ntfy app](https://ntfy.sh) on your phone (Android or iOS)
2. In the ntfy app, subscribe to a topic name (e.g., `my-lab-microscope-2024`)
3. In the Alerts tab, check **Enable push notifications**
4. Enter the same topic name in the **Topic** field
5. Click **Test Notification** to verify -- you should receive a notification on your phone
6. Choose which events trigger notifications using the event toggles

> **Tip:** Use a unique, hard-to-guess topic name since ntfy.sh topics are public by default. No account or API key is required.

## Output

No persistent output files. Connection and alert settings are saved to QuPath preferences and persist across sessions. The Status tab provides a real-time connection log.

## Tips & Troubleshooting

- **Run Test Connection first** to verify your setup before starting any workflow
- If connection fails, verify that the Python microscope server is running
- Check firewall settings if connecting to a remote host
- Health checks keep the connection alive during long operations
- If the server becomes unresponsive, use **Connect Now** to force a reconnection
- Auto-fallback to CLI mode allows limited operation when the socket server is unavailable
- For persistent connection issues, check the Advanced tab timeouts -- increase Read timeout for slow networks
- Connection log in the Status tab can help diagnose intermittent failures
- **Push notifications require internet access** on the QuPath machine; failures are silent and never block workflows
- If test notifications don't arrive, verify your phone is subscribed to the exact same topic name

## See Also

- [Live Viewer](live-viewer.md) - First tool to open after establishing a connection
- [Bounded Acquisition](bounded-acquisition.md) - Requires an active server connection
- [Existing Image Acquisition](existing-image-acquisition.md) - Requires an active server connection
- [Microscope Alignment](microscope-alignment.md) - Requires an active server connection
- [Camera Control](camera-control.md) - Prompts for connection if not connected
