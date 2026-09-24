package br.com.suaempresa.sambamanager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import br.com.suaempresa.sambamanager.service.AppLog;
import br.com.suaempresa.sambamanager.service.AuditService;
import br.com.suaempresa.sambamanager.service.AutomaticMappingRestoreService;
import br.com.suaempresa.sambamanager.service.PasswordChangeService;
import br.com.suaempresa.sambamanager.service.SambaConfig;
import br.com.suaempresa.sambamanager.service.ShareCatalogService;
import br.com.suaempresa.sambamanager.service.ShareHierarchy;
import br.com.suaempresa.sambamanager.service.UpdateInfo;
import br.com.suaempresa.sambamanager.service.UpdateInstallerLauncher;
import br.com.suaempresa.sambamanager.service.UpdateService;
import br.com.suaempresa.sambamanager.service.WindowsDriveMappingService;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
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
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;

public class SambaManagerApp extends Application {
    private static final String ADMIN_SHARE = "Administracao";
    private static final String EASTER_EGG_SEQUENCE = "0117721123826";
    // Altere para true quando a atualização manual de mapeamentos for reativada.
    private static final boolean REFRESH_MAPPINGS_ENABLED = false;
    private final SambaConfig config = SambaConfig.load();
    private final WindowsDriveMappingService mappingService = new WindowsDriveMappingService();
    private final PasswordChangeService passwordChangeService = new PasswordChangeService();
    private final AuditService auditService = new AuditService();
    private final UpdateService updateService = new UpdateService();
    private final ShareCatalogService shareCatalogService = new ShareCatalogService();
    private final List<CheckBox> shareBoxes = new ArrayList<>();

    private record AccessCheck(List<String> catalog, List<String> accessible, List<String> newShares,
            List<String> newDrives, boolean localCatalog, boolean automaticMappingFailed) { }

    @Override
    public void start(Stage stage) {
        AppLog.info("Aplicativo iniciado. Arquivo de log: " + AppLog.file());
        TextField username = new TextField();
        username.setPromptText("ex.: nome.sobrenome");
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
        shares.setHgap(26);
        shares.setVgap(8);
        shares.setPadding(new Insets(2, 0, 2, 0));
        populateShareCatalog(shares, config.shares());

        Button map = new Button("Mapear pastas");
        map.setDisable(true);
        Button refresh = new Button("Atualizar pastas");
        refresh.setDisable(!REFRESH_MAPPINGS_ENABLED);
        Button clearMappings = new Button("Limpar mapeamentos");
        // A limpeza não depende de autenticação: ela atua nas conexões SMB já
        // existentes deste computador e deve estar disponível desde a abertura.
        clearMappings.setDisable(false);
        Button changePassword = new Button("Alterar senha");
        changePassword.setDisable(true);
        username.setOnAction(event -> password.requestFocus());
        password.setOnAction(event -> enter.fire());
        visibleLoginPassword.setOnAction(event -> enter.fire());
        Label sharesTitle = new Label("Pastas disponíveis para este usuário");
        sharesTitle.setMaxWidth(Double.MAX_VALUE);
        sharesTitle.setAlignment(Pos.CENTER);
        GridPane actions = new GridPane();
        actions.setHgap(10);
        actions.getColumnConstraints().addAll(actionColumn(), actionColumn(), actionColumn(), actionColumn());
        map.setMaxWidth(Double.MAX_VALUE);
        refresh.setMaxWidth(Double.MAX_VALUE);
        clearMappings.setMaxWidth(Double.MAX_VALUE);
        changePassword.setMaxWidth(Double.MAX_VALUE);
        actions.addRow(0, map, refresh, clearMappings, changePassword);
        String currentVersion = System.getProperty("samba.manager.version", "desenvolvimento");
        Label versionLabel = new Label("Versão " + currentVersion);
        versionLabel.setStyle("-fx-text-fill: #707070; -fx-font-size: 10px;");
        Button updateButton = new Button("Atualizar");
        updateButton.setVisible(false);
        updateButton.setManaged(false);
        updateButton.setStyle("-fx-font-size: 10px; -fx-padding: 2 8 2 8;");
        HBox versionBar = new HBox(7, versionLabel, updateButton);
        versionBar.setAlignment(Pos.CENTER_RIGHT);
        VBox footer = new VBox(7, actions, versionBar);
        HBox shareArea = new HBox(shares);
        shareArea.setAlignment(Pos.CENTER);
        shareArea.setMaxWidth(Double.MAX_VALUE);
        ScrollPane shareViewport = new ScrollPane(shareArea);
        shareViewport.setFitToWidth(true);
        shareViewport.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        shareViewport.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        shareViewport.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
        VBox content = new VBox(9, sharesTitle, shareViewport);
        VBox.setVgrow(shareViewport, Priority.ALWAYS);
        content.setPadding(new Insets(24, 0, 0, 0));
        content.setDisable(true);

        enter.setOnAction(event -> {
            if (EASTER_EGG_SEQUENCE.equals(password.getText())) {
                password.clear();
                showEasterEgg();
                return;
            }
            if (username.getText().isBlank() || password.getText().isEmpty()) {
                showError("Informe o usuário e a senha.");
                return;
            }
            String currentUser = username.getText().trim();
            char[] currentPassword = password.getText().toCharArray();
            char[] passwordForRestore = currentPassword.clone();
            char[] passwordForNewShares = currentPassword.clone();
            java.util.concurrent.atomic.AtomicBoolean restoreFailed = new java.util.concurrent.atomic.AtomicBoolean();
            AppLog.info("Usuário " + currentUser + " solicitou verificação de acesso.");
            enter.setDisable(true);
            content.setDisable(true);
            status.setText("Verificando permissões…");
            Task<AccessCheck> task = new Task<>() {
                @Override
                protected AccessCheck call() throws Exception {
                    try {
                        List<String> catalog;
                        boolean localCatalog = false;
                        try {
                            catalog = shareCatalogService.fetch(config.sharesApiUrl());
                        } catch (Exception failure) {
                            AppLog.error("Não foi possível carregar a lista atual do servidor; usando a lista local.", failure);
                            catalog = config.shares();
                            localCatalog = true;
                        }
                        List<String> accessible = mappingService.checkAccessibleShares(config.server(), currentUser,
                                currentPassword, catalog);
                        List<String> remembered;
                        try {
                            remembered = mappingService.rememberedShares(config.server(), currentUser);
                        } catch (Exception failure) {
                            AppLog.error("Não foi possível consultar os mapeamentos anteriores.", failure);
                            remembered = List.of();
                        }
                        if (!accessible.isEmpty() && !remembered.isEmpty()
                                && AutomaticMappingRestoreService.available()) {
                            try {
                                AutomaticMappingRestoreService.enable(config.server(), currentUser, passwordForRestore);
                            } catch (Exception failure) {
                                AppLog.error("O acesso foi verificado, mas a reconexão automática não foi ativada.", failure);
                                restoreFailed.set(true);
                            }
                        }
                        boolean administrator = accessible.contains(ADMIN_SHARE);
                        List<String> rememberedForUser = remembered;
                        List<String> newShares = localCatalog || administrator ? List.of()
                                : accessible.stream()
                                        .filter(share -> config.shares().stream()
                                                .noneMatch(existing -> existing.equalsIgnoreCase(share)))
                                        .filter(share -> rememberedForUser.stream()
                                                .noneMatch(existing -> existing.equalsIgnoreCase(share)))
                                        .toList();
                        List<String> newDrives = List.of();
                        boolean mappingFailed = false;
                        if (!remembered.isEmpty() && !newShares.isEmpty()) {
                            try {
                                AppLog.info("Novos compartilhamentos autorizados para " + currentUser + ": "
                                        + String.join(", ", newShares));
                                newDrives = mappingService.map(config.server(), currentUser,
                                        passwordForNewShares.clone(), newShares);
                                mappingFailed = newDrives.size() != newShares.size();
                            } catch (Exception failure) {
                                AppLog.error("Não foi possível mapear automaticamente as novas pastas.", failure);
                                mappingFailed = true;
                            }
                        }
                        return new AccessCheck(catalog, accessible, newShares, newDrives,
                                localCatalog, mappingFailed);
                    } finally {
                        java.util.Arrays.fill(passwordForRestore, '\0');
                        java.util.Arrays.fill(passwordForNewShares, '\0');
                    }
                }
            };
            task.setOnSucceeded(done -> {
                AccessCheck result = task.getValue();
                List<String> accessible = result.accessible();
                AppLog.info("Verificação concluída. Pastas disponíveis: " + accessible.size() + " - "
                        + String.join(", ", accessible));
                recordConnectionAudit(currentUser, currentVersion, accessible.size());
                boolean administrator = accessible.contains(ADMIN_SHARE);
                populateShareCatalog(shares, result.catalog());
                updateShareView(shares, accessible, administrator);
                List<String> availableToMap = shareBoxes.stream()
                        .filter(this::isShareAccessible)
                        .map(this::shareOf)
                        .toList();
                content.setDisable(false);
                map.setDisable(availableToMap.isEmpty());
                refresh.setDisable(!REFRESH_MAPPINGS_ENABLED || availableToMap.isEmpty());
                changePassword.setDisable(false);
                enter.setDisable(false);
                status.setText(administrator
                        ? "Acesso administrativo identificado em " + config.server()
                        : availableToMap.isEmpty()
                                ? "Nenhuma pasta disponível para este usuário."
                                : availableToMap.size() + " pasta(s) disponível(is) em " + config.server());

                if (result.localCatalog()) {
                    status.setText(status.getText() + " Lista local em uso.");
                }
                if (!result.newShares().isEmpty()) {
                    String names = String.join(", ", result.newShares());
                    if (result.automaticMappingFailed()) {
                        status.setText("Nova pasta disponível; mapeamento automático incompleto.");
                        new Alert(Alert.AlertType.WARNING,
                                "Nova(s) pasta(s) disponível(is): " + names
                                        + "\nNão foi possível mapear todas automaticamente. Use Mapear pastas.",
                                ButtonType.OK).show();
                    } else if (!result.newDrives().isEmpty()) {
                        status.setText("Nova(s) pasta(s) mapeada(s): " + names);
                        new Alert(Alert.AlertType.INFORMATION,
                                "Nova(s) pasta(s) disponível(is) e mapeada(s): " + names,
                                ButtonType.OK).show();
                    } else {
                        status.setText("Nova(s) pasta(s) disponível(is): " + names);
                    }
                }

                if (administrator) {
                    AppLog.info("Usuário " + currentUser
                            + " identificado como administrador. Somente o compartilhamento Administração será mapeado.");
                }
                if (restoreFailed.get()) {
                    new Alert(Alert.AlertType.WARNING,
                            "As pastas estão acessíveis, mas a reconexão automática não foi ativada. Consulte o log do programa.",
                            ButtonType.OK).show();
                }

            });
            task.setOnFailed(failed -> {
                AppLog.error("Falha na verificação de permissões.", task.getException());
                enter.setDisable(false);
                status.setText("Não foi possível verificar as permissões.");
                showError(task.getException().getMessage());
            });
            new Thread(task, "samba-access-checker").start();
        });

        map.setOnAction(event -> {
            List<String> selected = shareBoxes.stream().filter(this::isShareAccessible).map(this::shareOf).toList();
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
                refresh.setDisable(!REFRESH_MAPPINGS_ENABLED);
                password.clear();
                List<String> drives = task.getValue();
                status.setText(drives.size() == selected.size()
                        ? "Mapeamento concluído: " + String.join(", ", drives)
                        : "Mapeamento parcial: " + drives.size() + " de " + selected.size()
                                + " pasta(s). Consulte o log.");
                showMappingResultAndOpenExplorer(drives.size(), selected.size());
            });
            task.setOnFailed(failed -> {
                AppLog.error("Falha no mapeamento.", task.getException());
                map.setDisable(false);
                refresh.setDisable(!REFRESH_MAPPINGS_ENABLED);
                password.clear();
                status.setText("Não foi possível concluir o mapeamento.");
                showError(task.getException().getMessage());
            });
            new Thread(task, "samba-drive-mapper").start();
        });

        refresh.setOnAction(event -> {
            List<String> selected = shareBoxes.stream().filter(this::isShareAccessible).map(this::shareOf).toList();
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
                refresh.setDisable(!REFRESH_MAPPINGS_ENABLED);
                password.clear();
                status.setText("Mapeamentos atualizados: " + String.join(", ", task.getValue()));
            });
            task.setOnFailed(failed -> {
                AppLog.error("Falha na atualização dos mapeamentos.", task.getException());
                map.setDisable(false);
                refresh.setDisable(!REFRESH_MAPPINGS_ENABLED);
                password.clear();
                status.setText("Não foi possível atualizar os mapeamentos.");
                showError(task.getException().getMessage());
            });
            new Thread(task, "samba-drive-refresher").start();
        });

        clearMappings.setOnAction(event -> {
            Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                    "Isso removerá somente os mapeamentos do Servidor de arquivos deste computador. Deseja continuar?",
                    ButtonType.YES, ButtonType.NO);
            confirmation.setHeaderText("Limpar mapeamentos");
            if (confirmation.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) {
                return;
            }
            clearMappings.setDisable(true);
            status.setText("Limpando mapeamentos…");
            Task<List<String>> task = new Task<>() {
                @Override
                protected List<String> call() throws Exception {
                    return mappingService.clearMappings(config.server());
                }
            };
            task.setOnSucceeded(done -> {
                clearMappings.setDisable(false);
                List<String> removed = task.getValue();
                AppLog.info("Mapeamentos removidos do servidor " + config.server() + ": "
                        + (removed.isEmpty() ? "nenhum" : String.join(", ", removed)));
                status.setText(removed.isEmpty() ? "Nenhum mapeamento encontrado neste computador."
                        : "Mapeamentos removidos: " + String.join(", ", removed));
                if (!removed.isEmpty()) {
                    offerRestart();
                }
            });
            task.setOnFailed(failed -> {
                clearMappings.setDisable(false);
                AppLog.error("Falha ao limpar mapeamentos do servidor " + config.server() + ".", task.getException());
                status.setText("Não foi possível limpar os mapeamentos.");
                showError(task.getException().getMessage());
            });
            new Thread(task, "samba-drive-cleaner").start();
        });

        changePassword.setOnAction(event -> showPasswordDialog(username.getText().trim(), status));

        BorderPane root = new BorderPane(content);
        HBox loginActions = new HBox(10, enter, status);
        loginActions.setAlignment(Pos.CENTER_LEFT);
        root.setTop(new VBox(12, new Label("Gerenciador de Acesso ao Servidor de Arquivos"), login, loginActions));
        root.setBottom(footer);
        BorderPane.setMargin(footer, new Insets(14, 0, 0, 0));
        root.setPadding(new Insets(18));
        stage.setTitle("Royal Server Access");
        var iconUrl = SambaManagerApp.class.getResource("/icons/server-access.png");
        if (iconUrl != null) {
            stage.getIcons().add(new Image(iconUrl.toExternalForm()));
        }
        stage.setMinWidth(560);
        stage.setMinHeight(640);
        Scene scene = new Scene(root, 560, 640);
        var shareStyles = SambaManagerApp.class.getResource("/styles/share-list.css");
        if (shareStyles != null) scene.getStylesheets().add(shareStyles.toExternalForm());
        stage.setScene(scene);
        stage.show();
        checkForUpdates(updateButton, currentVersion, status);
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message);
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    private void showMappingResultAndOpenExplorer(int mapped, int requested) {
        if (mapped == 0) {
            return;
        }
        String message = mapped == requested
                ? "Pastas mapeadas com sucesso! Ao fechar esta mensagem, o Explorador abrirá em Este Computador."
                : mapped + " de " + requested + " pasta(s) foram mapeadas. Consulte o log para ver as demais."
                        + " Ao fechar esta mensagem, o Explorador abrirá em Este Computador.";
        Alert result = new Alert(mapped == requested ? Alert.AlertType.INFORMATION : Alert.AlertType.WARNING,
                message, ButtonType.OK);
        result.setTitle("Mapeamento concluído");
        result.setHeaderText(null);
        result.showAndWait();
        try {
            new ProcessBuilder("explorer.exe", "shell:MyComputerFolder").start();
            AppLog.info("Explorador de Arquivos aberto em Este Computador após o mapeamento.");
        } catch (IOException exception) {
            AppLog.error("Não foi possível abrir o Explorador após o mapeamento.", exception);
            showError("As pastas foram mapeadas, mas não foi possível abrir o Explorador de Arquivos.");
        }
    }

    private void recordConnectionAudit(String username, String appVersion, int accessibleCount) {
        Thread auditThread = new Thread(() -> {
            try {
                String computerName = System.getenv().getOrDefault("COMPUTERNAME", "unknown");
                auditService.recordConnection(config.auditApiUrl(), username, computerName,
                        appVersion, accessibleCount);
                AppLog.info("Conexão registrada no log de auditoria do servidor.");
            } catch (Exception exception) {
                // Uma indisponibilidade do log não deve impedir o usuário de acessar as pastas.
                AppLog.error("Não foi possível registrar a conexão no servidor.", exception);
            }
        }, "samba-connection-audit");
        auditThread.setDaemon(true);
        auditThread.start();
    }

    private void showEasterEgg() {
        Alert confirmation = new Alert(
                Alert.AlertType.CONFIRMATION,
                "Deseja prosseguir?",
                ButtonType.OK,
                ButtonType.CANCEL);
        confirmation.setTitle("Mensagem especial");
        confirmation.setHeaderText(null);
        if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }

        try (InputStream input = SambaManagerApp.class.getResourceAsStream("/.ne")) {
            if (input == null) {
                throw new IOException("O arquivo .ne não foi encontrado.");
            }
            String message = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
            Alert specialMessage = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
            specialMessage.setTitle("Para você");
            specialMessage.setHeaderText(null);
            specialMessage.showAndWait();
        } catch (IOException exception) {
            AppLog.error("Não foi possível abrir a mensagem especial.", exception);
            showError("Não foi possível abrir a mensagem especial.");
        }
    }

    private String shareOf(CheckBox box) {
        return (String) box.getUserData();
    }

    private boolean isShareAccessible(CheckBox box) {
        return Boolean.TRUE.equals(box.getProperties().get("shareAccessible"));
    }

    private void checkForUpdates(Button updateButton, String currentVersion, Label status) {
        Task<UpdateInfo> task = new Task<>() {
            @Override
            protected UpdateInfo call() throws Exception {
                return updateService.check(config.updateApiUrl());
            }
        };
        task.setOnSucceeded(event -> {
            UpdateInfo update = task.getValue();
            if (compareVersions(update.version(), currentVersion) > 0) {
                updateButton.setText("Atualizar para " + update.version());
                updateButton.setUserData(update);
                updateButton.setVisible(true);
                updateButton.setManaged(true);
                AppLog.info("Atualização disponível: versão instalada=" + currentVersion
                        + ", versão disponível=" + update.version() + ".");
                updateButton.setOnAction(click -> startUpdate(updateButton, status, update));
            } else {
                AppLog.info("Aplicativo atualizado. Versão instalada=" + currentVersion
                        + ", versão disponível=" + update.version() + ".");
            }
        });
        task.setOnFailed(event -> AppLog.error("Não foi possível consultar atualizações.", task.getException()));
        Thread checker = new Thread(task, "samba-update-checker");
        checker.setDaemon(true);
        checker.start();
    }

    private void startUpdate(Button updateButton, Label status, UpdateInfo update) {
        ButtonType install = new ButtonType("Baixar e instalar",
                javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        ButtonType later = new ButtonType("Depois", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                "Versão disponível: " + update.version() + "\n\n" + update.notes()
                        + "\n\nO instalador será baixado, verificado e aberto automaticamente.",
                install, later);
        confirmation.setTitle("Atualização disponível");
        confirmation.setHeaderText(update.required() ? "Atualização necessária" : "Existe uma nova versão");
        if (confirmation.showAndWait().orElse(later) != install) {
            return;
        }
        updateButton.setDisable(true);
        updateButton.setText("Baixando…");
        status.setText("Baixando atualização " + update.version() + "…");
        Task<Path> task = new Task<>() {
            @Override
            protected Path call() throws Exception {
                return updateService.download(update);
            }
        };
        task.setOnSucceeded(event -> {
            Path installer = task.getValue();
            try {
                AppLog.info("Abrindo o instalador da atualização " + update.version() + ".");
                UpdateInstallerLauncher.launchCentered(installer);
                Platform.exit();
            } catch (Exception exception) {
                updateButton.setDisable(false);
                updateButton.setText("Atualizar para " + update.version());
                status.setText("Não foi possível abrir o instalador.");
                AppLog.error("Falha ao abrir o instalador da atualização.", exception);
                showError("A atualização foi baixada, mas o instalador não pôde ser aberto.");
            }
        });
        task.setOnFailed(event -> {
            updateButton.setDisable(false);
            updateButton.setText("Atualizar para " + update.version());
            status.setText("Não foi possível instalar a atualização.");
            AppLog.error("Falha ao baixar ou validar a atualização " + update.version() + ".", task.getException());
            showError(task.getException().getMessage());
        });
        Thread downloader = new Thread(task, "samba-update-downloader");
        downloader.setDaemon(true);
        downloader.start();
    }

    private int compareVersions(String left, String right) {
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        int size = Math.max(leftParts.length, rightParts.length);
        for (int index = 0; index < size; index++) {
            int leftPart = index < leftParts.length ? numericPart(leftParts[index]) : 0;
            int rightPart = index < rightParts.length ? numericPart(rightParts[index]) : 0;
            if (leftPart != rightPart) {
                return Integer.compare(leftPart, rightPart);
            }
        }
        return 0;
    }

    private int numericPart(String value) {
        String digits = value.replaceFirst("[^0-9].*$", "");
        if (digits.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private void populateShareCatalog(GridPane shares, List<String> catalog) {
        shareBoxes.clear();
        for (String share : catalog) {
            CheckBox box = new CheckBox();
            box.setUserData(share);
            // The check mark reports access; users cannot change it themselves.
            box.setMouseTransparent(true);
            box.setFocusTraversable(false);
            Label denied = new Label("×");
            denied.setMouseTransparent(true);
            denied.setStyle("-fx-text-fill: #c62828; -fx-font-size: 15px; -fx-font-weight: bold;");
            denied.setTranslateY(-1);
            denied.visibleProperty().bind(box.selectedProperty().or(box.indeterminateProperty()).not());
            StackPane indicator = new StackPane(box, denied);
            Label name = new Label(share);
            name.setStyle("-fx-text-fill: black;");
            HBox row = new HBox(4, indicator, name);
            row.setAlignment(Pos.CENTER_LEFT);
            box.getProperties().put("shareRow", row);
            box.getProperties().put("shareNameLabel", name);
            box.getProperties().put("shareAccessible", false);
            shareBoxes.add(box);
        }
        renderShareCatalog(shares, List.of(), false);
    }

    private void updateShareView(GridPane shares, List<String> accessible, boolean administrator) {
        for (CheckBox box : shareBoxes) {
            boolean hasAccess = accessible.contains(shareOf(box));
            box.getProperties().put("shareAccessible", hasAccess);
            box.setIndeterminate(false);
            box.setSelected(hasAccess);
        }
        renderShareCatalog(shares, accessible, administrator);
    }

    private void renderShareCatalog(GridPane shares, List<String> accessible, boolean administrator) {
        shares.getChildren().clear();
        List<CheckBox> visible = shareBoxes.stream()
                .filter(box -> administrator == ADMIN_SHARE.equals(shareOf(box)))
                .toList();
        List<String> names = visible.stream().map(this::shareOf).toList();
        var children = ShareHierarchy.childrenOf(names);
        int index = 0;
        for (CheckBox box : visible) {
            String share = shareOf(box);
            if (ShareHierarchy.parentOf(share, names) != null) continue;
            shares.add(shareGroup(share, null, children, visible, accessible), index % 2, index / 2);
            index++;
        }
    }

    private Node shareGroup(String share, String parent, java.util.Map<String, List<String>> children,
            List<CheckBox> visible, List<String> accessible) {
        CheckBox box = visible.stream().filter(item -> shareOf(item).equals(share)).findFirst().orElseThrow();
        HBox row = (HBox) box.getProperties().get("shareRow");
        Label name = (Label) box.getProperties().get("shareNameLabel");
        name.setText(ShareHierarchy.childLabel(share, parent));
        List<String> subshares = children.get(share);
        if (subshares.isEmpty()) return row;

        // An indeterminate checkbox is a group marker, not permission to map
        // the parent share. Mapping still uses the separately stored access.
        box.getStyleClass().add("share-group");
        box.setAllowIndeterminate(true);
        box.setSelected(false);
        box.setIndeterminate(true);

        VBox nested = new VBox(6);
        for (String child : subshares) {
            nested.getChildren().add(shareGroup(child, share, children, visible, accessible));
        }
        nested.setPadding(new Insets(2, 0, 3, 10));
        nested.setVisible(false);
        nested.setManaged(false);
        long available = subshares.stream().filter(accessible::contains).count();
        Button toggle = new Button("▸");
        toggle.setFocusTraversable(false);
        toggle.setStyle("-fx-background-color: transparent; -fx-padding: 0 3 0 0; -fx-font-size: 14px;");
        toggle.setAccessibleText("Mostrar subpastas de " + share);
        toggle.setOnAction(event -> {
            boolean expanded = !nested.isVisible();
            nested.setVisible(expanded);
            nested.setManaged(expanded);
            toggle.setText(expanded ? "▾" : "▸");
            toggle.setAccessibleText((expanded ? "Ocultar" : "Mostrar") + " subpastas de " + share);
        });
        Label count = new Label(available > 0 ? "(" + available + " disponível)" : "(" + subshares.size() + " subpasta)");
        count.setStyle("-fx-text-fill: #707070; -fx-font-size: 10px;");
        HBox header = new HBox(2, toggle, row, count);
        header.setAlignment(Pos.CENTER_LEFT);
        return new VBox(3, header, nested);
    }

    private void offerRestart() {
        ButtonType restartNow = new ButtonType("Reiniciar agora", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        ButtonType restartLater = new ButtonType("Depois", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert restart = new Alert(Alert.AlertType.INFORMATION,
                "Os mapeamentos foram removidos. Reinicie o computador para concluir a limpeza das conexões do servidor.",
                restartNow, restartLater);
        restart.setTitle("Reinicialização necessária");
        restart.setHeaderText("A limpeza foi concluída");
        if (restart.showAndWait().orElse(restartLater) == restartNow) {
            try {
                AppLog.info("Usuário solicitou a reinicialização do computador após limpar os mapeamentos.");
                new ProcessBuilder("shutdown.exe", "/r", "/t", "0").start();
            } catch (Exception exception) {
                AppLog.error("Não foi possível iniciar a reinicialização do computador.", exception);
                showError("Não foi possível reiniciar o computador. Reinicie-o manualmente.");
            }
        }
    }

    private ColumnConstraints actionColumn() {
        ColumnConstraints column = new ColumnConstraints();
        column.setPercentWidth(25);
        return column;
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
        if (choice.isEmpty() || choice.get() != submit)
            return;
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
            AppLog.error("Falha na alteração de senha do usuário " + username + ".", task.getException());
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
