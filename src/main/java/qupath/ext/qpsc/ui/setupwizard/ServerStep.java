package qupath.ext.qpsc.ui.setupwizard;

import java.io.IOException;
import java.net.Socket;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Step 6: Server Connection.
 * Configures the microscope_command_server host and port, with an optional
 * connectivity test.
 */
public class ServerStep implements WizardStep {

    private static final Logger logger = LoggerFactory.getLogger(ServerStep.class);
    private static final int CONNECTION_TIMEOUT_MS = 3000;

    private final WizardData data;
    private final VBox content;

    private final TextField hostField;
    private final Spinner<Integer> portSpinner;
    private final Label statusLabel;
    private final Button testButton;

    public ServerStep(WizardData data, ResourceCatalog catalog) {
        this.data = data;

        content = new VBox(15);
        content.setPadding(new Insets(15));

        // Help text
        Label helpLabel = new Label("The QPSC extension communicates with a Python-based "
                + "microscope_command_server over a TCP socket connection. "
                + "Configure the server host and port here. The server does not "
                + "need to be running right now -- you can test the connection later.");
        helpLabel.setWrapText(true);

        // Form
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        Label hostLabel = new Label("Server host:");
        hostField = new TextField(data.serverHost);
        hostField.setPromptText("e.g., localhost or 192.168.1.100");

        Label portLabel = new Label("Server port:");
        SpinnerValueFactory.IntegerSpinnerValueFactory portFactory =
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 65535, data.serverPort);
        portSpinner = new Spinner<>(portFactory);
        portSpinner.setEditable(true);
        portSpinner.setPrefWidth(120);

        // Commit port spinner text on focus loss
        portSpinner.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) {
                commitPortValue();
            }
        });

        grid.add(hostLabel, 0, 0);
        grid.add(hostField, 1, 0);
        grid.add(portLabel, 0, 1);
        grid.add(portSpinner, 1, 1);

        // Test connection
        testButton = new Button("Test Connection");
        testButton.setOnAction(e -> testConnection());

        statusLabel = new Label("Not tested");
        statusLabel.setStyle("-fx-font-style: italic;");

        HBox testRow = new HBox(10, testButton, statusLabel);
        testRow.setAlignment(Pos.CENTER_LEFT);

        // Note about optional step
        Label noteLabel = new Label("Note: This step is optional. The server may not be running yet "
                + "during initial setup. You can always update these settings later "
                + "in QPSC Preferences.");
        noteLabel.setWrapText(true);
        noteLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666666;");

        content.getChildren().addAll(helpLabel, grid, testRow, noteLabel);
    }

    private void commitPortValue() {
        try {
            String text = portSpinner.getEditor().getText().trim();
            int value = Integer.parseInt(text);
            if (value >= 1 && value <= 65535) {
                portSpinner.getValueFactory().setValue(value);
            } else {
                portSpinner.getEditor().setText(String.valueOf(portSpinner.getValue()));
            }
        } catch (NumberFormatException e) {
            portSpinner.getEditor().setText(String.valueOf(portSpinner.getValue()));
        }
    }

    /**
     * Attempt a TCP socket connection to the configured host:port in a background thread.
     */
    private void testConnection() {
        commitPortValue();

        String host = hostField.getText().trim();
        int port = portSpinner.getValue();

        if (host.isEmpty()) {
            statusLabel.setText("Error: host is empty");
            statusLabel.setStyle("-fx-font-style: italic; -fx-text-fill: red;");
            return;
        }

        statusLabel.setText("Testing...");
        statusLabel.setStyle("-fx-font-style: italic; -fx-text-fill: #666666;");
        testButton.setDisable(true);

        Thread testThread = new Thread(
                () -> {
                    boolean success = false;
                    String errorMsg = "";
                    try (Socket socket = new Socket()) {
                        socket.connect(new java.net.InetSocketAddress(host, port), CONNECTION_TIMEOUT_MS);
                        success = true;
                    } catch (IOException e) {
                        errorMsg = e.getMessage();
                    }

                    final boolean connected = success;
                    final String msg = errorMsg;
                    Platform.runLater(() -> {
                        testButton.setDisable(false);
                        if (connected) {
                            statusLabel.setText("Connected successfully to " + host + ":" + port);
                            statusLabel.setStyle("-fx-font-style: normal; -fx-text-fill: green;");
                            logger.info("Server connection test succeeded: {}:{}", host, port);
                        } else {
                            statusLabel.setText("Connection failed: " + msg);
                            statusLabel.setStyle("-fx-font-style: normal; -fx-text-fill: red;");
                            logger.debug("Server connection test failed: {}:{} - {}", host, port, msg);
                        }
                    });
                },
                "ServerStep-ConnectionTest");
        testThread.setDaemon(true);
        testThread.start();
    }

    @Override
    public String getTitle() {
        return "Server";
    }

    @Override
    public String getDescription() {
        return "Configure the microscope command server connection.";
    }

    @Override
    public Node getContent() {
        return content;
    }

    @Override
    public String validate() {
        // This step is optional -- validation always passes
        return null;
    }

    @Override
    public void onEnter() {
        hostField.setText(data.serverHost);
        portSpinner.getValueFactory().setValue(data.serverPort);
    }

    @Override
    public void onLeave() {
        commitPortValue();
        data.serverHost = hostField.getText().trim();
        if (data.serverHost.isEmpty()) {
            data.serverHost = "localhost";
        }
        data.serverPort = portSpinner.getValue();
        logger.debug("ServerStep: saved host={}, port={}", data.serverHost, data.serverPort);
    }
}
