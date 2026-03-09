# Server Connection Settings

> Menu: Extensions > QP Scope > Server Connection Settings...
> [Back to README](../../README.md) | [All Tools](../UTILITIES.md)

## Purpose

Configure and test the connection between QuPath and the microscope control server. The Python microscope server handles communication with Micro-Manager and the physical microscope hardware. This tool lets you set the connection parameters, test connectivity, and monitor connection health.

## Prerequisites

- Python microscope server installed and running (or ready to start)
- Network access between QuPath and the server (localhost for local, or appropriate firewall rules for remote)

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

### Status Tab

| Feature | Description |
|---------|-------------|
| Connection Status | Current state of the connection |
| Connection Log | Timestamped log of connection events |
| Clear Log | Button to reset the log display |

## Workflow

1. Open Server Connection Settings from the menu
2. Verify the Host and Port match your microscope server configuration
3. Click **Test Connection** to verify communication
4. If successful, the test returns the current stage position
5. Click **Connect Now** to establish the connection (or enable Auto-connect for future sessions)
6. Monitor connection health in the Status tab

## Output

No persistent output files. Connection settings are saved to QuPath preferences and persist across sessions. The Status tab provides a real-time connection log.

## Tips & Troubleshooting

- **Run Test Connection first** to verify your setup before starting any workflow
- If connection fails, verify that the Python microscope server is running
- Check firewall settings if connecting to a remote host
- Health checks keep the connection alive during long operations
- If the server becomes unresponsive, use **Connect Now** to force a reconnection
- Auto-fallback to CLI mode allows limited operation when the socket server is unavailable
- For persistent connection issues, check the Advanced tab timeouts -- increase Read timeout for slow networks
- Connection log in the Status tab can help diagnose intermittent failures

## See Also

- [Live Viewer](live-viewer.md) - First tool to open after establishing a connection
- [Bounded Acquisition](bounded-acquisition.md) - Requires an active server connection
- [Existing Image Acquisition](existing-image-acquisition.md) - Requires an active server connection
- [Microscope Alignment](microscope-alignment.md) - Requires an active server connection
- [Camera Control](camera-control.md) - Prompts for connection if not connected
