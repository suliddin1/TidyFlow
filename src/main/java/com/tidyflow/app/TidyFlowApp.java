package com.tidyflow.app;

import com.tidyflow.core.dedupe.DesktopTrashService;
import com.tidyflow.core.dedupe.DuplicateFinder;
import com.tidyflow.core.dedupe.DuplicateRemover;
import com.tidyflow.core.executor.FileExecutor;
import com.tidyflow.core.executor.UndoManager;
import com.tidyflow.core.rules.DefaultRules;
import com.tidyflow.core.rules.RuleEngine;
import com.tidyflow.core.scanner.FolderScanner;
import com.tidyflow.data.BatchRepository;
import com.tidyflow.data.DatabaseManager;
import com.tidyflow.data.FileIndexRepository;
import com.tidyflow.data.OperationRepository;
import com.tidyflow.data.config.RuleRepository;
import com.tidyflow.ui.MainController;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.net.URL;

/**
 * JavaFX Application entry point that initializes SQLite migrations and wires services into the UI.
 */
public class TidyFlowApp extends Application {

    @Override
    public void start(Stage primaryStage) throws Exception {
        DatabaseManager dbManager = new DatabaseManager();
        dbManager.migrate();

        BatchRepository batchRepository = new BatchRepository(dbManager);
        OperationRepository operationRepository = new OperationRepository(dbManager);
        FileIndexRepository fileIndexRepository = new FileIndexRepository(dbManager);
        RuleRepository ruleRepository = new RuleRepository();

        FolderScanner scanner = new FolderScanner();
        RuleEngine ruleEngine = new RuleEngine(DefaultRules.create());
        FileExecutor executor = new FileExecutor(batchRepository, operationRepository);
        UndoManager undoManager = new UndoManager(operationRepository);
        DuplicateFinder duplicateFinder = new DuplicateFinder(fileIndexRepository);
        DuplicateRemover duplicateRemover = new DuplicateRemover(
                new DesktopTrashService(),
                batchRepository,
                operationRepository
        );

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/tidyflow/ui/main-view.fxml"));
        loader.setControllerFactory(type -> {
            if (type == MainController.class) {
                return new MainController(
                        scanner,
                        ruleEngine,
                        executor,
                        batchRepository,
                        operationRepository,
                        undoManager,
                        duplicateFinder,
                        duplicateRemover,
                        ruleRepository
                );
            }
            try {
                return type.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new RuntimeException("Failed to instantiate controller: " + type, e);
            }
        });

        Parent root = loader.load();
        Scene scene = new Scene(root);

        URL stylesheet = getClass().getResource("/com/tidyflow/ui/styles.css");
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet.toExternalForm());
        }

        primaryStage.setTitle("TidyFlow");
        primaryStage.setScene(scene);
        primaryStage.show();
    }
}
