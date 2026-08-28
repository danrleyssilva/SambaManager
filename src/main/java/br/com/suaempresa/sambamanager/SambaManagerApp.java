package br.com.suaempresa.sambamanager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import br.com.suaempresa.sambamanager.service.AppLog;
import br.com.suaempresa.sambamanager.service.PasswordChangeService;
import br.com.suaempresa.sambamanager.service.SambaConfig;
import br.com.suaempresa.sambamanager.service.WindowsDriveMappingService;
import javafx.application.Application;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;

public class SambaManagerApp extends Application {
    private final SambaConfig config = SambaConfig.load();
    private final WindowsDriveMappingService mappingService = new WindowsDriveMappingService();
    private final PasswordChangeService passwordChangeService = new PasswordChangeService();
    private final List<CheckBox> shareBoxes = new ArrayList<>();

    @Override
    public void start(Stage stage) {
        AppLog.info("Aplicativo iniciado. Arquivo de log: " + AppLog.file());
        TextField username = new TextField();
        username.setPromptText("ex.: nome.ultimo");
        PasswordField password = new PasswordField();
        TextField visibleLoginPassword = visibleCopy(password);

        GridPane login = new GridPane();
        login.setHgap(10);
        login.setVgap(10);
        login.addRow(0, new Label("Usuário:"), username);
        login.addRow(1, new Label("Senha:"), passwordInput(password, visibleLoginPassword));

        Button enter = new Button("Entrar");
        Label status = new Label("Servidor: " + config.server());

        GridPane shares = new GridPane();
        shares.setHgap(38);
        shares.setVgap(7);
        for (int index = 0; index < config.shares().size(); index++) {
            String share = config.shares().get(index);
            CheckBox box = new CheckBox(share);
            shareBoxes.add(box);
            shares.add(box, index % 2, index / 2);
        }

        Button map = new Button("Mapear selecionadas");
        map.setDisable(true);
        Button refresh = new Button("Atualizar mapeamentos");
        refresh.setDisable(true);
        Button changePassword = new Button("Alterar senha");
        ScrollPane shareScroll = new ScrollPane(shares);
        shareScroll.setFitToWidth(true);
        shareScroll.setPrefViewportHeight(300);
        VBox content = new VBox(12, new Label("Compartilhamentos disponíveis"), shareScroll, new HBox(10, map, refresh, changePassword));
        content.setDisable(true);

        enter.setOnAction(event -> {
            if (username.getText().isBlank() || password.getText().isEmpty()) {
                showError("Informe o usuário e a senha.");
                return;
            }
            String currentUser = username.getText().trim();
            char[] currentPassword = password.getText().toCharArray();
            AppLog.info("Usuário " + currentUser + " solicitou verificação de acesso.");
            enter.setDisable(true);
            status.setText("Verificando permissões…");
            Task<List<String>> task = new Task<>() {
                @Override
                protected List<String> call() throws Exception {
                    return mappingService.checkAccessibleShares(config.server(), currentUser, currentPassword, config.shares());
                }
            };
            task.setOnSucceeded(done -> {
                List<String> accessible = task.getValue();
                AppLog.info("Verificação concluída. Pastas disponíveis: " + accessible.size() + " - " + String.join(", ", accessible));
                shareBoxes.forEach(box -> box.setSelected(accessible.contains(box.getText())));
                content.setDisable(false);
                map.setDisable(accessible.isEmpty());
                refresh.setDisable(accessible.isEmpty());
                enter.setDisable(false);
                status.setText(accessible.isEmpty()
                        ? "Nenhuma pasta disponível para este usuário."
                        : accessible.size() + " pasta(s) disponível(is) em " + config.server());
            });
            task.setOnFailed(failed -> {
                AppLog.error("Falha na verificação de permissões.", (Exception) task.getException());
                enter.setDisable(false);
                status.setText("Não foi possível verificar as permissões.");
                showError(task.getException().getMessage());
            });
            new Thread(task, "samba-access-checker").start();
        });

        map.setOnAction(event -> {
            List<String> selected = shareBoxes.stream().filter(CheckBox::isSelected).map(CheckBox::getText).toList();
            if (selected.isEmpty()) {
                showError("Selecione pelo menos uma pasta.");
                return;
            }
            String currentUser = username.getText().trim();
            char[] currentPassword = password.getText().toCharArray();
            AppLog.info("Usuário " + currentUser + " solicitou mapeamento: " + String.join(", ", selected));
            map.setDisable(true);
            refresh.setDisable(true);
            status.setText("Mapeando pastas…");
            Task<List<String>> task = new Task<>() {
                @Override
                protected List<String> call() throws Exception {
                    return mappingService.map(config.server(), currentUser, currentPassword, selected);
                }
            };
            task.setOnSucceeded(done -> {
                map.setDisable(false);
                refresh.setDisable(false);
                password.clear();
                status.setText("Mapeamento concluído: " + String.join(", ", task.getValue()));
            });
            task.setOnFailed(failed -> {
                AppLog.error("Falha no mapeamento.", (Exception) task.getException());
                map.setDisable(false);
                refresh.setDisable(false);
                password.clear();
                status.setText("Não foi possível concluir o mapeamento.");
                showError(task.getException().getMessage());
            });
            new Thread(task, "samba-drive-mapper").start();
        });

        refresh.setOnAction(event -> {
            List<String> selected = shareBoxes.stream().filter(CheckBox::isSelected).map(CheckBox::getText).toList();
            if (selected.isEmpty()) {
                showError("Selecione pelo menos uma pasta.");
                return;
            }
            if (password.getText().isEmpty()) {
                showError("Informe a senha novamente para atualizar os mapeamentos.");
                return;
            }
            String currentUser = username.getText().trim();
            char[] currentPassword = password.getText().toCharArray();
            AppLog.info("Usuário " + currentUser + " solicitou atualização: " + String.join(", ", selected));
            map.setDisable(true);
            refresh.setDisable(true);
            status.setText("Atualizando mapeamentos…");
            Task<List<String>> task = new Task<>() {
                @Override
                protected List<String> call() throws Exception {
                    return mappingService.refreshMappings(config.server(), currentUser, currentPassword, selected);
                }
            };
            task.setOnSucceeded(done -> {
                map.setDisable(false);
                refresh.setDisable(false);
                password.clear();
                status.setText("Mapeamentos atualizados: " + String.join(", ", task.getValue()));
            });
            task.setOnFailed(failed -> {
                AppLog.error("Falha na atualização dos mapeamentos.", (Exception) task.getException());
                map.setDisable(false);
                refresh.setDisable(false);
                password.clear();
                status.setText("Não foi possível atualizar os mapeamentos.");
                showError(task.getException().getMessage());
            });
            new Thread(task, "samba-drive-refresher").start();
        });

        changePassword.setOnAction(event -> showPasswordDialog(username.getText().trim(), status));

        BorderPane root = new BorderPane(content);
        root.setTop(new VBox(12, new Label("Gerenciador de Acesso Samba"), login, new HBox(10, enter, status)));
        root.setPadding(new Insets(18));
        stage.setTitle("Samba Manager");
        stage.setScene(new Scene(root, 620, 560));
        stage.show();
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message);
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    private void showPasswordDialog(String username, Label status) {
        if (username.isBlank()) {
            showError("Informe o usuário antes de alterar a senha.");
            return;
        }
        PasswordField current = new PasswordField();
        PasswordField next = new PasswordField();
        PasswordField confirm = new PasswordField();
        TextField currentVisible = visibleCopy(current);
        TextField nextVisible = visibleCopy(next);
        TextField confirmVisible = visibleCopy(confirm);
        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        form.addRow(0, new Label("Senha atual:"), passwordInput(current, currentVisible));
        form.addRow(1, new Label("Nova senha:"), passwordInput(next, nextVisible));
        form.addRow(2, new Label("Confirmar senha:"), passwordInput(confirm, confirmVisible));
        ButtonType submit = new ButtonType("Alterar senha", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Alterar senha");
        dialog.setHeaderText("Usuário: " + username);
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getButtonTypes().addAll(submit, ButtonType.CANCEL);
        Button submitButton = (Button) dialog.getDialogPane().lookupButton(submit);
        submitButton.addEventFilter(ActionEvent.ACTION, event -> {
            if (current.getText().isEmpty() || next.getText().isEmpty()) {
                event.consume();
                showError("Preencha todos os campos de senha.");
            } else if (next.getText().length() < 8) {
                event.consume();
                next.clear();
                confirm.clear();
                showError("A nova senha deve ter pelo menos 8 caracteres.");
            } else if (!next.getText().equals(confirm.getText())) {
                event.consume();
                next.clear();
                confirm.clear();
                showError("A confirmação não corresponde à nova senha.");
            }
        });
        Optional<ButtonType> choice = dialog.showAndWait();
        if (choice.isEmpty() || choice.get() != submit) return;
        char[] oldPassword = current.getText().toCharArray();
        char[] newPassword = next.getText().toCharArray();
        status.setText("Alterando senha…");
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                passwordChangeService.change(config.passwordApiUrl(), username, oldPassword, newPassword);
                return null;
            }
        };
        task.setOnSucceeded(event -> {
            AppLog.info("Senha alterada com sucesso para o usuário " + username + ".");
            status.setText("Senha alterada com sucesso.");
            Alert alert = new Alert(Alert.AlertType.INFORMATION, "Sua senha de acesso foi alterada.");
            alert.setHeaderText(null);
            alert.showAndWait();
        });
        task.setOnFailed(event -> {
            AppLog.error("Falha na alteração de senha do usuário " + username + ".", (Exception) task.getException());
            status.setText("Não foi possível alterar a senha.");
            showError(task.getException().getMessage());
        });
        new Thread(task, "samba-password-changer").start();
    }

    private TextField visibleCopy(PasswordField password) {
        TextField visible = new TextField();
        visible.textProperty().bindBidirectional(password.textProperty());
        visible.setVisible(false);
        visible.setManaged(false);
        return visible;
    }

    private StackPane passwordInput(PasswordField masked, TextField visible) {
        masked.setStyle("-fx-padding: 0 36 0 8;");
        visible.setStyle("-fx-padding: 0 36 0 8;");
        Button toggle = new Button();
        toggle.setGraphic(eyeIcon(false));
        toggle.setStyle("-fx-background-color: transparent; -fx-padding: 4 9 4 9; -fx-cursor: hand;");
        toggle.setOnAction(event -> {
            boolean show = !visible.isVisible();
            masked.setVisible(!show);
            masked.setManaged(!show);
            visible.setVisible(show);
            visible.setManaged(show);
            toggle.setGraphic(eyeIcon(show));
        });
        StackPane input = new StackPane(masked, visible, toggle);
        input.setPrefWidth(200);
        input.setMinWidth(200);
        input.setMaxWidth(200);
        StackPane.setAlignment(toggle, Pos.CENTER_RIGHT);
        return input;
    }

    private Group eyeIcon(boolean shown) {
        SVGPath outline = new SVGPath();
        outline.setContent("M1,12 C4,6 8,4 12,4 C16,4 20,6 23,12 C20,18 16,20 12,20 C8,20 4,18 1,12 Z");
        outline.setFill(Color.TRANSPARENT);
        outline.setStroke(Color.web("#8AA0BE"));
        outline.setStrokeWidth(1.8);
        SVGPath pupil = new SVGPath();
        pupil.setContent("M12,8 A4,4 0 1,0 12.1,8 Z");
        pupil.setFill(Color.web("#8AA0BE"));
        Group icon = new Group(outline, pupil);
        if (!shown) {
            Line slash = new Line(2, 3, 22, 21);
            slash.setStroke(Color.web("#8AA0BE"));
            slash.setStrokeWidth(2);
            icon.getChildren().add(slash);
        }
        icon.setScaleX(0.72);
        icon.setScaleY(0.72);
        return icon;
    }
}
