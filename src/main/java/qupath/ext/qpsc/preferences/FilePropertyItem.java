package qupath.ext.qpsc.preferences;

import java.io.File;
import java.util.Optional;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ObservableValue;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import org.controlsfx.control.PropertySheet;
import org.controlsfx.property.editor.PropertyEditor;

/**
 * A PropertySheet.Item for file/directory paths that opens the FileChooser
 * at the current value's parent directory instead of the system default.
 * <p>
 * ControlsFX's built-in file editor does not set the initial directory,
 * forcing users to navigate from the root every time.
 */
public class FilePropertyItem implements PropertySheet.Item {

    private final StringProperty property;
    private final String name;
    private final String category;
    private final String description;
    private final boolean directoryMode;

    /**
     * @param property      the string property holding the file/directory path
     * @param name          display name in the preference sheet
     * @param category      preference category
     * @param description   tooltip/description text
     * @param directoryMode true for directory chooser, false for file chooser
     */
    public FilePropertyItem(
            StringProperty property, String name, String category, String description, boolean directoryMode) {
        this.property = property;
        this.name = name;
        this.category = category;
        this.description = description;
        this.directoryMode = directoryMode;
    }

    @Override
    public Class<?> getType() {
        return File.class;
    }

    @Override
    public String getCategory() {
        return category;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public Object getValue() {
        String path = property.get();
        return (path != null && !path.isEmpty()) ? new File(path) : null;
    }

    @Override
    public void setValue(Object value) {
        if (value instanceof File) {
            property.set(((File) value).getAbsolutePath());
        } else if (value instanceof String) {
            property.set((String) value);
        } else if (value == null) {
            property.set("");
        }
    }

    @Override
    public Optional<ObservableValue<?>> getObservableValue() {
        return Optional.of(property);
    }

    @Override
    public Optional<Class<? extends PropertyEditor<?>>> getPropertyEditorClass() {
        return Optional.of(directoryMode ? DirEditor.class : FileEditor.class);
    }

    private static File getInitialDir(PropertySheet.Item item) {
        Object val = item.getValue();
        if (val instanceof File) {
            File f = (File) val;
            File parent = f.isDirectory() ? f : f.getParentFile();
            if (parent != null && parent.exists()) {
                return parent;
            }
        }
        return null;
    }

    public static class FileEditor implements PropertyEditor<File> {
        private final PropertySheet.Item item;
        private final HBox editor;
        private final TextField textField;

        public FileEditor(PropertySheet.Item item) {
            this.item = item;
            textField = new TextField();
            textField.setEditable(true);
            HBox.setHgrow(textField, Priority.ALWAYS);

            // Initialize from current value
            Object val = item.getValue();
            if (val instanceof File) {
                textField.setText(((File) val).getAbsolutePath());
            }

            Button browseBtn = new Button("...");
            browseBtn.setOnAction(e -> {
                FileChooser fc = new FileChooser();
                File initDir = getInitialDir(item);
                if (initDir != null) {
                    fc.setInitialDirectory(initDir);
                }
                File chosen = fc.showOpenDialog(browseBtn.getScene().getWindow());
                if (chosen != null) {
                    setValue(chosen);
                }
            });

            textField.setOnAction(e -> {
                String text = textField.getText();
                if (text != null && !text.isEmpty()) {
                    setValue(new File(text));
                }
            });

            editor = new HBox(4, textField, browseBtn);
        }

        @Override
        public Node getEditor() {
            return editor;
        }

        @Override
        public File getValue() {
            Object val = item.getValue();
            return (val instanceof File) ? (File) val : null;
        }

        @Override
        public void setValue(File value) {
            item.setValue(value);
            if (value != null) {
                textField.setText(value.getAbsolutePath());
            }
        }
    }

    public static class DirEditor implements PropertyEditor<File> {
        private final PropertySheet.Item item;
        private final HBox editor;
        private final TextField textField;

        public DirEditor(PropertySheet.Item item) {
            this.item = item;
            textField = new TextField();
            textField.setEditable(true);
            HBox.setHgrow(textField, Priority.ALWAYS);

            Object val = item.getValue();
            if (val instanceof File) {
                textField.setText(((File) val).getAbsolutePath());
            }

            Button browseBtn = new Button("...");
            browseBtn.setOnAction(e -> {
                DirectoryChooser dc = new DirectoryChooser();
                File initDir = getInitialDir(item);
                if (initDir != null) {
                    dc.setInitialDirectory(initDir);
                }
                File chosen = dc.showDialog(browseBtn.getScene().getWindow());
                if (chosen != null) {
                    setValue(chosen);
                }
            });

            textField.setOnAction(e -> {
                String text = textField.getText();
                if (text != null && !text.isEmpty()) {
                    setValue(new File(text));
                }
            });

            editor = new HBox(4, textField, browseBtn);
        }

        @Override
        public Node getEditor() {
            return editor;
        }

        @Override
        public File getValue() {
            Object val = item.getValue();
            return (val instanceof File) ? (File) val : null;
        }

        @Override
        public void setValue(File value) {
            item.setValue(value);
            if (value != null) {
                textField.setText(value.getAbsolutePath());
            }
        }
    }
}
