package org.phoebus.logbook.olog.ui;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Pagination;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Callback;
import javafx.util.Duration;
import javafx.util.StringConverter;
import org.phoebus.applications.logbook.authentication.OlogAuthenticationScope;
import org.phoebus.core.websocket.client.WebSocketMessageHandler;
import org.phoebus.framework.jobs.JobManager;
import org.phoebus.logbook.LogClient;
import org.phoebus.logbook.LogEntry;
import org.phoebus.logbook.LogService;
import org.phoebus.logbook.LogbookException;
import org.phoebus.logbook.LogbookPreferences;
import org.phoebus.logbook.SearchResult;
import org.phoebus.logbook.olog.ui.query.OlogQuery;
import org.phoebus.logbook.olog.ui.query.OlogQueryManager;
import org.phoebus.logbook.olog.ui.spi.Decoration;
import org.phoebus.logbook.olog.ui.write.EditMode;
import org.phoebus.logbook.olog.ui.write.LogEntryEditorStage;
import org.phoebus.olog.es.api.model.LogGroupProperty;
import org.phoebus.olog.es.api.model.OlogLog;
import org.phoebus.security.store.SecureStore;
import org.phoebus.security.tokens.ScopedAuthenticationToken;
import org.phoebus.ui.dialog.DialogHelper;
import org.phoebus.ui.dialog.ExceptionDetailsErrorDialog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A controller for a log entry table with a collapsible advance search section.
 *
 * @author Kunal Shroff
 */
public class LogEntryTableViewController extends LogbookSearchController implements WebSocketMessageHandler {

    @FXML
    @SuppressWarnings("unused")
    private ComboBox<OlogQuery> query;

    // elements associated with the various search
    @FXML
    @SuppressWarnings("unused")
    private GridPane viewSearchPane;

    @SuppressWarnings("unused")
    @FXML
    private SplitPane splitPane;

    // elements related to the table view of the log entries
    @FXML
    @SuppressWarnings("unused")
    private TableView<TableViewListItem> tableView;
    @FXML
    @SuppressWarnings({"UnusedDeclaration"})
    private LogEntryDisplayController logEntryDisplayController;
    @FXML
    @SuppressWarnings("unused")
    private ProgressIndicator progressIndicator;
    @FXML
    @SuppressWarnings({"UnusedDeclaration"})
    private AdvancedSearchViewController advancedSearchViewController;

    @FXML
    @SuppressWarnings("unused")
    private Pagination pagination;
    @FXML
    @SuppressWarnings("unused")
    private Node searchResultView;

    @FXML
    @SuppressWarnings("unused")
    private TextField pageSizeTextField;

    @SuppressWarnings("unused")
    @FXML
    private Pane logDetailView;

    @SuppressWarnings("unused")
    @FXML
    private VBox errorPane;

    @FXML
    @SuppressWarnings("unused")
    private Label openAdvancedSearchLabel;
    // Model
    private SearchResult searchResult;

    /**
     * List of selected log entries
     */
    private final ObservableList<LogEntry> selectedLogEntries = FXCollections.observableArrayList();
    private static final Logger logger = Logger.getLogger(LogEntryTableViewController.class.getName());

    private final SimpleBooleanProperty showDetails = new SimpleBooleanProperty();
    private final SimpleBooleanProperty advancedSearchVisible = new SimpleBooleanProperty(false);


    /**
     * Constructor.
     *
     * @param logClient Log client implementation
     */
    public LogEntryTableViewController(LogClient logClient,
                                       OlogQueryManager ologQueryManager,
                                       SearchParameters searchParameters) {
        setClient(logClient);
        this.ologQueryManager = ologQueryManager;
        this.searchParameters = searchParameters;
    }

    protected void setGoBackAndGoForwardActions(LogEntryTable.GoBackAndGoForwardActions goBackAndGoForwardActions) {
        this.goBackAndGoForwardActions = Optional.of(goBackAndGoForwardActions);
    }

    private final SimpleIntegerProperty hitCountProperty = new SimpleIntegerProperty(0);
    private final SimpleIntegerProperty pageSizeProperty =
            new SimpleIntegerProperty(LogbookUIPreferences.search_result_page_size);
    private final SimpleIntegerProperty pageCountProperty = new SimpleIntegerProperty(0);
    private final OlogQueryManager ologQueryManager;
    private final ObservableList<OlogQuery> ologQueries = FXCollections.observableArrayList();
    private final SimpleBooleanProperty userHasSignedIn = new SimpleBooleanProperty(false);

    private final SearchParameters searchParameters;

    protected Optional<LogEntryTable.GoBackAndGoForwardActions> goBackAndGoForwardActions = Optional.empty();

    @FXML
    public void initialize() {
        super.initialize();

        logEntryDisplayController.setLogEntryTableViewController(this);

        advancedSearchViewController.setSearchCallback(this::search);

        configureComboBox();
        ologQueries.setAll(ologQueryManager.getQueries());

        searchParameters.addListener((observable, oldValue, newValue) -> {
            query.getEditor().setText(newValue);
        });

        tableView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        MenuItem groupSelectedEntries = new MenuItem(Messages.GroupSelectedEntries);
        groupSelectedEntries.setOnAction(e -> createLogEntryGroup());

        groupSelectedEntries.disableProperty()
                .bind(Bindings.createBooleanBinding(() ->
                        selectedLogEntries.size() < 2 || userHasSignedIn.not().get(), selectedLogEntries, userHasSignedIn));
        ContextMenu contextMenu = new ContextMenu();

        MenuItem menuItemShowHideAll = new MenuItem(Messages.ShowHideDetails);
        menuItemShowHideAll.acceleratorProperty().setValue(new KeyCodeCombination(KeyCode.D, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN));
        menuItemShowHideAll.setOnAction(ae -> {
            showDetails.set(!showDetails.get());
            tableView.getItems().forEach(item -> item.setShowDetails(!item.isShowDetails().get()));
        });

        MenuItem menuItemNewLogEntry = new MenuItem(Messages.NewLogEntry);
        menuItemNewLogEntry.acceleratorProperty().setValue(new KeyCodeCombination(KeyCode.N, KeyCombination.CONTROL_DOWN));
        menuItemNewLogEntry.setOnAction(ae -> new LogEntryEditorStage(new OlogLog(), null, EditMode.NEW_LOG_ENTRY).showAndWait());

        MenuItem menuItemUpdateLogEntry = new MenuItem(Messages.UpdateLogEntry);
        menuItemUpdateLogEntry.visibleProperty().bind(Bindings.createBooleanBinding(() -> selectedLogEntries.size() == 1, selectedLogEntries));
        menuItemUpdateLogEntry.acceleratorProperty().setValue(new KeyCodeCombination(KeyCode.U, KeyCombination.CONTROL_DOWN));
        menuItemUpdateLogEntry.setOnAction(ae -> new LogEntryEditorStage(selectedLogEntries.get(0), null, EditMode.UPDATE_LOG_ENTRY).show());

        contextMenu.getItems().addAll(groupSelectedEntries, menuItemShowHideAll, menuItemNewLogEntry);
        if (LogbookUIPreferences.log_entry_update_support) {
            contextMenu.getItems().add(menuItemUpdateLogEntry);
        }

        MenuItem menuItemPrint = new MenuItem(Messages.PrintSearchResults);
        menuItemPrint.setOnAction(ae -> printSearchResults());
        contextMenu.getItems().add(menuItemPrint);
        contextMenu.setOnShowing(e -> {
            try {
                SecureStore store = new SecureStore();
                ScopedAuthenticationToken scopedAuthenticationToken = store.getScopedAuthenticationToken(new OlogAuthenticationScope());
                userHasSignedIn.set(scopedAuthenticationToken != null);
            } catch (Exception ex) {
                logger.log(Level.WARNING, "Secure Store file not found.", ex);
            }
        });

        tableView.setContextMenu(contextMenu);

        // The display table.
        tableView.getColumns().clear();
        tableView.setEditable(false);
        tableView.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            // Update detailed view, but only if selection contains a single item.
            if (newValue != null && tableView.getSelectionModel().getSelectedItems().size() == 1) {
                if (goBackAndGoForwardActions.isPresent() && !goBackAndGoForwardActions.get().getIsRecordingHistoryDisabled()) {
                    goBackAndGoForwardActions.get().addGoBackAction();
                    goBackAndGoForwardActions.get().goForwardActions.clear();
                }
                logEntryDisplayController.setLogEntry(newValue.getLogEntry());
            }
            List<LogEntry> logEntries = tableView.getSelectionModel().getSelectedItems()
                    .stream().map(TableViewListItem::getLogEntry).collect(Collectors.toList());
            selectedLogEntries.setAll(logEntries);
        });

        tableView.getStylesheets().add(this.getClass().getResource("/search_result_view.css").toExternalForm());
        pagination.getStylesheets().add(this.getClass().getResource("/pagination.css").toExternalForm());

        TableColumn<TableViewListItem, TableViewListItem> descriptionCol = new TableColumn<>();
        descriptionCol.setMaxWidth(1f * Integer.MAX_VALUE * 100);
        descriptionCol.setCellValueFactory(col -> new SimpleObjectProperty<>(col.getValue()));
        descriptionCol.setCellFactory(col -> new TableCell<>() {
            {
                setStyle("-fx-padding: -1px");
            }

            private final Node graphic;
            private final PseudoClass childlessTopLevel =
                    PseudoClass.getPseudoClass("grouped");
            private final LogEntryCellController controller;

            {
                try {
                    FXMLLoader loader = new FXMLLoader(getClass().getResource("LogEntryCell.fxml"));
                    graphic = loader.load();
                    controller = loader.getController();
                    controller.setDecorations(decorations);
                } catch (IOException exc) {
                    throw new RuntimeException(exc);
                }
            }

            @Override
            public void updateItem(TableViewListItem logEntry, boolean empty) {
                super.updateItem(logEntry, empty);
                if (empty) {
                    setGraphic(null);
                    pseudoClassStateChanged(childlessTopLevel, false);
                } else {
                    controller.setLogEntry(logEntry);
                    setGraphic(graphic);
                    boolean b = LogGroupProperty.getLogGroupProperty(logEntry.getLogEntry()).isPresent();
                    pseudoClassStateChanged(childlessTopLevel, b);
                }
            }
        });

        tableView.getColumns().add(descriptionCol);
        tableView.setPlaceholder(new Label(Messages.NoSearchResults));

        progressIndicator.visibleProperty().bind(searchInProgress);

        searchResultView.disableProperty().bind(searchInProgress);

        pagination.currentPageIndexProperty().addListener((a, b, c) -> search());

        pageSizeTextField.setText(Integer.toString(pageSizeProperty.get()));

        Pattern DIGIT_PATTERN = Pattern.compile("\\d*");
        // This is to accept numerical input only, and at most 3 digits (maximizing search to 999 hits).
        pageSizeTextField.textProperty().addListener((observable, oldValue, newValue) -> {
            if (DIGIT_PATTERN.matcher(newValue).matches()) {
                if (newValue.isEmpty()) {
                    pageSizeProperty.set(LogbookUIPreferences.search_result_page_size);
                } else if (newValue.length() > 3) {
                    pageSizeTextField.setText(oldValue);
                } else {
                    pageSizeProperty.set(Integer.parseInt(newValue));
                }
            } else {
                pageSizeTextField.setText(oldValue);
            }
        });

        // Hide the pagination widget if hit count == 0 or page count < 2
        pagination.visibleProperty().bind(Bindings.createBooleanBinding(() -> hitCountProperty.get() > 0 && pagination.pageCountProperty().get() > 1,
                hitCountProperty, pagination.pageCountProperty()));
        pagination.pageCountProperty().bind(pageCountProperty);
        pagination.maxPageIndicatorCountProperty().bind(pageCountProperty);

        query.itemsProperty().bind(new SimpleObjectProperty<>(ologQueries));

        query.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                search();
            }
        });
        query.getEditor().setText(ologQueries.get(0).getQuery());
        query.getSelectionModel().select(ologQueries.get(0));
        searchParameters.setQuery(ologQueries.get(0).getQuery());

        openAdvancedSearchLabel.setOnMouseClicked(e -> resize());

        openAdvancedSearchLabel.textProperty()
                .bind(Bindings.createStringBinding(() -> advancedSearchVisible.get() ?
                                Messages.AdvancedSearchHide : Messages.AdvancedSearchOpen,
                        advancedSearchVisible));

        determineConnectivity(connectivityMode -> {
            connectivityModeObjectProperty.set(connectivityMode);
            connectivityCheckerCountDownLatch.countDown();
            switch (connectivityMode) {
                case HTTP_ONLY -> search();
                case WEB_SOCKETS_SUPPORTED -> connectWebSocket();
            }
        });

    }

    // Keeps track of when the animation is active. Multiple clicks will be ignored
    // until a give resize action is completed
    private final AtomicBoolean moving = new AtomicBoolean(false);

    @FXML
    public void resize() {
        if (!moving.compareAndExchangeAcquire(false, true)) {
            Duration cycleDuration = Duration.millis(400);
            Timeline timeline;
            if (advancedSearchVisible.get()) {
                query.disableProperty().set(false);
                KeyValue kv = new KeyValue(advancedSearchViewController.getPane().minWidthProperty(), 0);
                KeyValue kv2 = new KeyValue(advancedSearchViewController.getPane().maxWidthProperty(), 0);
                timeline = new Timeline(new KeyFrame(cycleDuration, kv, kv2));
                timeline.setOnFinished(event -> {
                    advancedSearchVisible.set(false);
                    moving.set(false);
                    search();
                });
            } else {
                searchParameters.setQuery(query.getEditor().getText());
                double width = viewSearchPane.getWidth() / 2.5;
                KeyValue kv = new KeyValue(advancedSearchViewController.getPane().minWidthProperty(), width);
                KeyValue kv2 = new KeyValue(advancedSearchViewController.getPane().prefWidthProperty(), width);
                timeline = new Timeline(new KeyFrame(cycleDuration, kv, kv2));
                timeline.setOnFinished(event -> {
                    advancedSearchVisible.set(true);
                    moving.set(false);
                    query.disableProperty().set(true);
                });
            }
            timeline.play();
        }
    }

    /**
     * Performs a single search based on the current query. If the search completes successfully,
     * the UI is updated and a periodic search is launched using the same query. If on the other hand
     * the search fails (service off-line or invalid query), a periodic search is NOT launched.
     */
    @Override
    public void search() {
        // In case the page size text field is empty, or the value is zero, set the page size to the default
        if ("".equals(pageSizeTextField.getText()) || Integer.parseInt(pageSizeTextField.getText()) == 0) {
            pageSizeTextField.setText(Integer.toString(LogbookUIPreferences.search_result_page_size));
        }

        searchInProgress.set(true);

        String queryString = query.getEditor().getText();
        // Construct the query parameters from the search field string. Note that some keys
        // are treated as "hidden" and removed in the returned map.

        Map<String, String> params =
                LogbookQueryUtil.parseHumanReadableQueryString(ologQueryManager.getOrAddQuery(queryString).getQuery());
        params.put("sort", advancedSearchViewController.getSortAscending() ? "up" : "down");
        params.put("from", Integer.toString(pagination.getCurrentPageIndex() * pageSizeProperty.get()));
        params.put("size", Integer.toString(pageSizeProperty.get()));
        params.put("tz", ZoneId.systemDefault().getId());


        searchInProgress.set(true);
        logger.log(Level.INFO, "Single search: " + queryString);
        search(params,
                searchResult1 -> {
                    searchInProgress.set(false);
                    setSearchResult(searchResult1);
                    List<OlogQuery> queries = ologQueryManager.getQueries();
                    if (connectivityModeObjectProperty.get().equals(ConnectivityMode.HTTP_ONLY)) {
                        logger.log(Level.INFO, "Starting periodic search: " + queryString);
                        periodicSearch(params, this::setSearchResult);
                    }
                    Platform.runLater(() -> {
                        ologQueries.setAll(queries);
                        query.getSelectionModel().select(ologQueries.get(0));
                    });
                },
                (msg, ex) -> {
                    searchInProgress.set(false);
                    ExceptionDetailsErrorDialog.openError(splitPane, Messages.SearchFailed, "", ex);
                });
    }

    @Override
    public void setLogs(List<LogEntry> logs) {
        throw new RuntimeException(new UnsupportedOperationException());
    }

    private List<Decoration> decorations;

    protected void setDecorations(List<Decoration> decorations) {
        this.decorations = decorations;
        for (Decoration decoration : decorations) {
            decoration.setRefreshLogEntryTableView(() -> refresh());
        }
    }

    private void setSearchResult(SearchResult searchResult) {
        this.searchResult = searchResult;

        List<LogEntry> logEntries = searchResult.getLogs();
        decorations.forEach(decoration -> decoration.setLogEntries(logEntries));

        Platform.runLater(() -> {
            hitCountProperty.set(searchResult.getHitCount());
            pageCountProperty.set(1 + (hitCountProperty.get() / pageSizeProperty.get()));
            refresh();
        });
    }

    public void setQuery(String parsedQuery) {
        searchParameters.setQuery(parsedQuery);
        search();
    }

    public String getQuery() {
        return query.getValue().getQuery();
    }

    private void refresh() {
        Runnable refreshRunnable = () -> {
            if (this.searchResult != null) {
                List<TableViewListItem> selectedLogEntries = new ArrayList<>(tableView.getSelectionModel().getSelectedItems());

                List<LogEntry> logEntries = searchResult.getLogs();
                logEntries.sort((o1, o2) -> advancedSearchViewController.getSortAscending() ? o1.getCreatedDate().compareTo(o2.getCreatedDate()) :
                        -(o1.getCreatedDate().compareTo(o2.getCreatedDate())));

                boolean showDetailsBoolean = showDetails.get();
                var logs = logEntries.stream().map(le -> new TableViewListItem(le, showDetailsBoolean)).toList();

                ObservableList<TableViewListItem> logsList = FXCollections.observableArrayList(logs);
                tableView.setItems(logsList);

                // This will ensure that selected entries stay selected after the list has been
                // updated from the search result.
                for (TableViewListItem selectedItem : selectedLogEntries) {
                    for (TableViewListItem item : tableView.getItems()) {
                        if (item.getLogEntry().getId().equals(selectedItem.getLogEntry().getId())) {
                            if (goBackAndGoForwardActions.isPresent()) {
                                goBackAndGoForwardActions.get().setIsRecordingHistoryDisabled(true); // Do not create a "Back" action for the automatic reload.
                                tableView.getSelectionModel().select(item);
                                goBackAndGoForwardActions.get().setIsRecordingHistoryDisabled(false);
                            } else {
                                tableView.getSelectionModel().select(item);
                            }
                        }
                    }
                }
            }
        };

        if (Platform.isFxApplicationThread()) {
            refreshRunnable.run();
        } else {
            Platform.runLater(refreshRunnable);
        }
    }

    private void createLogEntryGroup() {
        List<Long> logEntryIds =
                selectedLogEntries.stream().map(LogEntry::getId).collect(Collectors.toList());
        JobManager.schedule("Group log entries", monitor -> {
            try {
                LogClient logClient = LogService.getInstance().getLogFactories().get(LogbookPreferences.logbook_factory).getLogClient();
                logClient.groupLogEntries(logEntryIds);
                search();
            } catch (LogbookException e) {
                logger.log(Level.INFO, "Unable to create log entry group from selection");
                Platform.runLater(() -> {
                    final Alert dialog = new Alert(AlertType.ERROR);
                    dialog.setHeaderText(Messages.GroupingFailed);
                    DialogHelper.positionDialog(dialog, tableView, 0, 0);
                    dialog.showAndWait();
                });
            }
        });
    }

    @FXML
    @SuppressWarnings("unused")
    public void goToFirstPage() {
        pagination.setCurrentPageIndex(0);
    }

    @FXML
    @SuppressWarnings("unused")
    public void goToLastPage() {
        pagination.setCurrentPageIndex(pagination.pageCountProperty().get() - 1);
    }

    private void configureComboBox() {
        Font defaultQueryFont = Font.font("Liberation Sans", FontWeight.BOLD, 12);
        Font defaultQueryFontRegular = Font.font("Liberation Sans", FontWeight.NORMAL, 12);
        query.setVisibleRowCount(OlogQueryManager.getInstance().getQueryListSize());
        // Needed to customize item rendering, e.g. default query rendered in bold.
        query.setCellFactory(
                new Callback<>() {
                    @Override
                    public ListCell<OlogQuery> call(ListView<OlogQuery> param) {
                        return new ListCell<>() {
                            @Override
                            public void updateItem(OlogQuery item,
                                                   boolean empty) {
                                super.updateItem(item, empty);
                                if (item != null) {
                                    setText(item.getQuery().isEmpty() ? "<empty>" : item.getQuery());
                                    if (item.isDefaultQuery()) {
                                        setFont(defaultQueryFont);
                                    } else {
                                        setFont(defaultQueryFontRegular);
                                    }
                                } else {
                                    setText(null);
                                }
                            }
                        };
                    }
                });

        // This is needed for the "editor" part of the ComboBox
        query.setConverter(
                new StringConverter<>() {
                    @Override
                    public String toString(OlogQuery query) {
                        if (query == null) {
                            return "";
                        } else {
                            return query.getQuery();
                        }
                    }

                    @Override
                    public OlogQuery fromString(String s) {
                        return new OlogQuery(s);
                    }
                });

    }

    /**
     * Wrapper class for a {@link LogEntry} and a flag indicating whether details of the
     * log entry meta-data should be rendered in the list view.
     */
    public static class TableViewListItem {
        private final SimpleBooleanProperty showDetails = new SimpleBooleanProperty(true);
        private final LogEntry logEntry;

        public TableViewListItem(LogEntry logEntry, boolean showDetails) {
            this.logEntry = logEntry;
            this.showDetails.set(showDetails);
        }

        public SimpleBooleanProperty isShowDetails() {
            return showDetails;
        }

        public LogEntry getLogEntry() {
            return logEntry;
        }

        public void setShowDetails(boolean show) {
            this.showDetails.set(show);
        }
    }

    public void setShowDetails(boolean show) {
        showDetails.set(show);
    }

    public boolean getShowDetails() {
        return showDetails.get();
    }

    @SuppressWarnings("unused")
    public void showHelp() {
        new HelpViewer(LogbookUIPreferences.search_help).show();
    }

    /**
     * Handler for a {@link LogEntry} change, new or updated.
     * A search is triggered to make sure the result list reflects the change, and
     * the detail view controller is called to refresh, if applicable.
     *
     * @param logEntry A {@link LogEntry}
     */
    public void logEntryChanged(LogEntry logEntry) {
        search();
        setLogEntry(logEntry);
    }

    protected LogEntry getLogEntry() {
        return logEntryDisplayController.getLogEntry();
    }

    protected void setLogEntry(LogEntry logEntry) {
        logEntryDisplayController.setLogEntry(logEntry);
    }

    /**
     * Selects a log entry as a result of an action outside the {@link TreeView}, but selection happens on the
     * {@link TreeView} item, if it exists (match on log entry id). If it does not exist, selection is cleared
     * anyway to indicate that user selected log entry is not visible in {@link TreeView}.
     *
     * @param logEntry User selected log entry.
     * @return <code>true</code> if user selected log entry is present in {@link TreeView}, otherwise
     * <code>false</code>.
     */
    public boolean selectLogEntry(LogEntry logEntry) {
        tableView.getSelectionModel().clearSelection();
        Optional<TableViewListItem> optional = tableView.getItems().stream().filter(i -> i.getLogEntry().getId().equals(logEntry.getId())).findFirst();
        if (optional.isPresent()) {
            tableView.getSelectionModel().select(optional.get());
            return true;
        }
        return false;
    }

    /**
     * Renders each log entry on its own page as a clean professional
     * HTML report with full metadata, rendered markdown body, embedded
     * attachment images (via temp files), and properties.  Downloads images in
     * a background thread, then saves the report as an HTML file and opens
     * it in the system browser (use Print → Save as PDF from the browser).
     *
     * Report layout parameters (font, title, colours) are read from
     * {@link LogbookUIPreferences}.
     */

    /** Check whether an attachment is an image by MIME type or file extension. */
    private static boolean isImageAttachment(org.phoebus.logbook.Attachment att) {
        String ct = att.getContentType();
        if (ct != null && ct.toLowerCase().startsWith("image")) return true;
        String fn = att.getName() != null ? att.getName().toLowerCase() : "";
        return fn.endsWith(".png") || fn.endsWith(".jpg") || fn.endsWith(".jpeg")
                || fn.endsWith(".gif") || fn.endsWith(".bmp") || fn.endsWith(".svg")
                || fn.endsWith(".webp") || fn.endsWith(".tiff") || fn.endsWith(".tif");
    }

    private void printSearchResults() {
        List<TableViewListItem> items = tableView.getItems();
        if (items == null || items.isEmpty()) {
            Alert alert = new Alert(AlertType.INFORMATION, Messages.PrintNoResults);
            alert.setHeaderText(null);
            alert.showAndWait();
            return;
        }

        // Olog service base URL
        String serviceUrl = org.phoebus.olog.es.api.Preferences.olog_url != null
                ? org.phoebus.olog.es.api.Preferences.olog_url : "";
        if (serviceUrl.endsWith("/")) serviceUrl = serviceUrl.substring(0, serviceUrl.length() - 1);
        final String svcUrl = serviceUrl;

        // ===== Read PDF preferences =====
        final String pdfFontFamily = LogbookUIPreferences.pdf_font_family != null
                ? LogbookUIPreferences.pdf_font_family : "'Times New Roman', Times, serif";
        final int pdfFontSize = LogbookUIPreferences.pdf_font_size > 0
                ? LogbookUIPreferences.pdf_font_size : 14;
        final String pdfTitle = LogbookUIPreferences.pdf_title != null
                ? LogbookUIPreferences.pdf_title : "Olog";
        final String pdfSubtitle = LogbookUIPreferences.pdf_subtitle != null
                ? LogbookUIPreferences.pdf_subtitle : "Electronic Logbook";
        final String pdfAccent = LogbookUIPreferences.pdf_accent_color != null
                ? LogbookUIPreferences.pdf_accent_color : "#1a237e";
        final boolean embedImages = LogbookUIPreferences.pdf_embed_images;
        final boolean showToc = LogbookUIPreferences.pdf_toc;

        // Derived font sizes (scale proportionally from base)
        final String titlePt   = (int)(pdfFontSize * 1.50) + "pt";   // entry title
        final String metaPt    = (int)(pdfFontSize * 0.92) + "pt";   // meta grid
        final String badgePt   = (int)(pdfFontSize * 0.83) + "pt";   // badges
        final String codePt    = (int)(pdfFontSize * 0.83) + "pt";   // code/pre
        final String smallPt   = (int)(pdfFontSize * 0.79) + "pt";   // captions
        final String footerPt  = (int)(pdfFontSize * 0.75) + "pt";   // page footer
        final String coverH1   = (int)(pdfFontSize * 2.67) + "pt";   // cover title
        final String coverSub  = (int)(pdfFontSize * 1.25) + "pt";   // cover subtitle
        final String basePt    = pdfFontSize + "pt";
        final String sectionPt = metaPt;

        // --- Progress dialog (shown on FX thread, updated from background thread) ---
        final javafx.scene.control.ProgressBar progressBar = new javafx.scene.control.ProgressBar(-1);
        progressBar.setPrefWidth(340);
        final javafx.scene.control.Label statusLabel = new javafx.scene.control.Label("Preparing HTML report...");
        statusLabel.setStyle("-fx-font-size: 13px;");
        final javafx.scene.layout.VBox progressBox = new javafx.scene.layout.VBox(10, statusLabel, progressBar);
        progressBox.setStyle("-fx-padding: 20; -fx-alignment: center-left;");
        final javafx.stage.Stage progressStage = new javafx.stage.Stage();
        progressStage.initStyle(javafx.stage.StageStyle.UTILITY);
        progressStage.setTitle("HTML Report");
        progressStage.setScene(new javafx.scene.Scene(progressBox, 380, 90));
        progressStage.setResizable(false);
        if (tableView.getScene() != null && tableView.getScene().getWindow() != null) {
            progressStage.initOwner(tableView.getScene().getWindow());
        }
        progressStage.show();

        // Count total images upfront for progress tracking
        final int totalImages;
        if (embedImages) {
            int cnt = 0;
            for (TableViewListItem it : items) {
                LogEntry e = it.getLogEntry();
                if (e.getAttachments() == null) continue;
                for (org.phoebus.logbook.Attachment a : e.getAttachments())
                    if (isImageAttachment(a)) cnt++;
            }
            totalImages = cnt;
        } else {
            totalImages = 0;
        }

        // Background: download images + build HTML
        JobManager.schedule("HTML Report", monitor -> {
            try {
                // ===== Optionally download attachment images to temp files =====
                // Use file:// URLs instead of base64 data URIs to keep HTML small
                Map<String, String> imageDataMap = new HashMap<>();
                java.util.List<java.io.File> tempImageFiles = new java.util.ArrayList<>();
                if (embedImages) {
                    final int[] imgCount = {0};
                    HttpClient httpClient = HttpClient.newBuilder()
                            .followRedirects(HttpClient.Redirect.NORMAL)
                            .build();
                    for (TableViewListItem item : items) {
                        LogEntry entry = item.getLogEntry();
                        if (entry.getAttachments() == null) continue;
                        for (org.phoebus.logbook.Attachment att : entry.getAttachments()) {
                            if (!isImageAttachment(att)) continue;
                            imgCount[0]++;
                            final int current = imgCount[0];
                            Platform.runLater(() -> {
                                statusLabel.setText("Downloading image " + current + " / " + totalImages + "...");
                                progressBar.setProgress((double) current / totalImages);
                            });
                            String key = entry.getId() + "/" + att.getName();
                            String encodedName = att.getName() != null
                                    ? URLEncoder.encode(att.getName(), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20")
                                    : "image";
                            String imgUrl = svcUrl + "/logs/attachments/" + entry.getId() + "/" + encodedName;
                            try {
                                HttpRequest req = HttpRequest.newBuilder()
                                        .uri(URI.create(imgUrl))
                                        .GET().build();
                                HttpResponse<byte[]> resp = httpClient.send(req,
                                        HttpResponse.BodyHandlers.ofByteArray());
                                if (resp.statusCode() == 200 && resp.body().length > 0) {
                                    // Determine extension from content type or filename
                                    String ct = att.getContentType();
                                    String ext = ".png";
                                    if (att.getName() != null) {
                                        int dot = att.getName().lastIndexOf('.');
                                        if (dot >= 0) ext = att.getName().substring(dot);
                                    } else if (ct != null && ct.startsWith("image/")) {
                                        ext = "." + ct.substring(6).split("[;+]")[0].trim();
                                    }
                                    Path imgTmp = Files.createTempFile("olog_img_", ext);
                                    Files.write(imgTmp, resp.body());
                                    imgTmp.toFile().deleteOnExit();
                                    tempImageFiles.add(imgTmp.toFile());
                                    imageDataMap.put(key, imgTmp.toUri().toString());
                                }
                            } catch (Exception ex) {
                                logger.log(Level.WARNING, "Failed to download image: " + imgUrl, ex);
                            }
                        }
                    }
                }

                // ===== Build HTML =====
                Platform.runLater(() -> {
                    statusLabel.setText("Building report layout (" + items.size() + " entries)...");
                    progressBar.setProgress(-1); // indeterminate
                });
                HtmlAwareController htmlConverter = new HtmlAwareController(svcUrl);

                java.time.format.DateTimeFormatter dateTimeFmt = java.time.format.DateTimeFormatter
                        .ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
                java.time.format.DateTimeFormatter dateFmt = java.time.format.DateTimeFormatter
                        .ofPattern("EEEE, d MMMM yyyy").withZone(ZoneId.systemDefault());
                java.time.format.DateTimeFormatter timeFmt = java.time.format.DateTimeFormatter
                        .ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
                java.time.format.DateTimeFormatter dayKeyFmt = java.time.format.DateTimeFormatter
                        .ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

                StringBuilder sb = new StringBuilder();
                sb.append("<!DOCTYPE html><html><head><meta charset='UTF-8'><style>\n");

                // ====== Global styles (from preferences) ======
                sb.append("@page { size: A4; margin: 16mm 14mm; }\n");
                sb.append("body { font-family: ").append(pdfFontFamily).append(";\n");
                sb.append("  font-size: ").append(basePt).append("; color: #222; margin: 0; padding: 0; line-height: 1.55; }\n");

                // Cover page
                sb.append(".cover { height: 100vh; display: flex; flex-direction: column;\n");
                sb.append("  justify-content: center; align-items: center; text-align: center;\n");
                sb.append("  page-break-after: always; padding: 40px; }\n");
                sb.append(".cover h1 { font-size: ").append(coverH1).append("; font-weight: bold; color: ").append(pdfAccent)
                        .append("; margin: 0 0 8px 0; letter-spacing: 1px; }\n");
                sb.append(".cover .sub { font-size: ").append(coverSub).append("; font-weight: bold; color: #444; margin: 4px 0; }\n");
                sb.append(".cover .line { width: 120px; height: 3px; background: ").append(pdfAccent).append("; margin: 24px auto; }\n");
                sb.append(".cover .date-range { font-size: ").append((int)(pdfFontSize * 1.17)).append("pt; color: #333; font-weight: bold; margin: 16px 0; }\n");
                sb.append(".cover .stats { font-size: ").append(basePt).append("; color: #666; margin-top: 12px; }\n");
                sb.append(".cover .generated { font-size: ").append(smallPt).append("; color: #aaa; margin-top: 40px; }\n");

                // TOC page
                sb.append(".toc-page { page-break-after: always; padding: 20px 8px; }\n");
                sb.append(".toc-title { font-size: ").append(titlePt).append("; font-weight: bold; color: ").append(pdfAccent)
                        .append("; border-bottom: 3px solid ").append(pdfAccent).append("; padding-bottom: 8px; margin-bottom: 20px; }\n");
                sb.append(".toc-entry { display: flex; justify-content: space-between; align-items: baseline;\n");
                sb.append("  padding: 4px 0; border-bottom: 1px dotted #ccc; font-size: ").append(basePt).append("; }\n");
                sb.append(".toc-entry-title { color: #222; font-weight: bold; flex: 1; overflow: hidden;\n");
                sb.append("  text-overflow: ellipsis; white-space: nowrap; padding-right: 12px; }\n");
                sb.append(".toc-entry-meta { color: #666; font-size: ").append(metaPt).append(";\n");
                sb.append("  white-space: nowrap; text-align: right; }\n");
                sb.append(".toc-day-header { font-size: ").append(metaPt).append("; font-weight: bold; color: ").append(pdfAccent)
                        .append("; margin: 16px 0 6px 0; text-transform: uppercase; letter-spacing: 0.5px; }\n");

                // Entry page
                sb.append(".entry-page { page-break-after: always; padding: 0 8px; }\n");
                sb.append(".entry-page:last-child { page-break-after: auto; }\n");

                // Header stripe
                sb.append(".entry-stripe { border-bottom: 3px solid ").append(pdfAccent)
                        .append("; padding: 10px 0 8px 0; margin-bottom: 14px; }\n");
                sb.append(".entry-title { font-size: ").append(titlePt).append("; font-weight: 700; color: ")
                        .append(pdfAccent).append("; margin: 0; }\n");
                sb.append(".entry-id { font-size: ").append(smallPt).append("; color: #999; }\n");

                // Metadata grid
                sb.append(".meta-grid { display: grid; grid-template-columns: auto 1fr; gap: 3px 16px;\n");
                sb.append("  font-size: ").append(metaPt).append("; margin-bottom: 16px; }\n");
                sb.append(".meta-label { font-weight: 700; color: #444; white-space: nowrap; }\n");
                sb.append(".meta-value { color: #333; }\n");

                // Badges
                sb.append(".badge { display: inline-block; padding: 2px 10px; border-radius: 10px;\n");
                sb.append("  font-size: ").append(badgePt).append("; font-weight: 600; margin-right: 5px; }\n");
                sb.append(".b-logbook { background: #e3f2fd; color: #1565c0; }\n");
                sb.append(".b-tag { background: #fff3e0; color: #e65100; }\n");
                sb.append(".b-level { background: #e8f5e9; color: #2e7d32; }\n");

                // Body
                sb.append(".entry-body { font-size: ").append(basePt).append("; margin-bottom: 18px; }\n");
                sb.append(".entry-body img { max-width: 100%; height: auto; margin: 8px 0; }\n");
                sb.append(".entry-body table { border-collapse: collapse; width: 100%; margin: 10px 0; }\n");
                sb.append(".entry-body td, .entry-body th { border: 1px solid #ccc; padding: 6px 10px; font-size: ").append(metaPt).append("; }\n");
                sb.append(".entry-body th { background: #f5f5f5; font-weight: 600; }\n");
                sb.append(".entry-body pre { background: #f7f7f7; padding: 10px; border-radius: 4px;\n");
                sb.append("  font-size: ").append(codePt).append("; overflow-x: auto; }\n");
                sb.append(".entry-body code { background: #f0f0f0; padding: 1px 4px; border-radius: 3px; font-size: ").append(codePt).append("; }\n");
                sb.append(".entry-body blockquote { border-left: 3px solid ").append(pdfAccent)
                        .append("; margin: 8px 0; padding: 4px 14px; color: #555; }\n");

                // Attachment images
                sb.append(".images-section { margin: 16px 0; }\n");
                sb.append(".images-section h3 { font-size: ").append(sectionPt)
                        .append("; color: #444; text-transform: uppercase; letter-spacing: 0.5px; margin: 0 0 10px 0; }\n");
                sb.append(".att-img { max-width: 100%; height: auto; margin: 0 0 12px 0; }\n");
                sb.append(".att-caption { font-size: ").append(smallPt).append("; color: #888; margin: -8px 0 12px 0; }\n");

                // Non-image attachments
                sb.append(".files-section { margin: 10px 0; font-size: ").append(metaPt).append("; color: #666; }\n");
                sb.append(".file-chip { display: inline-block; background: #f5f5f5; padding: 3px 12px;\n");
                sb.append("  border-radius: 12px; margin: 2px 4px 2px 0; font-size: ").append(badgePt).append("; }\n");

                // Properties
                sb.append(".props-section { margin: 16px 0; }\n");
                sb.append(".props-section h3 { font-size: ").append(sectionPt)
                        .append("; color: #444; text-transform: uppercase; letter-spacing: 0.5px; margin: 0 0 8px 0; }\n");
                sb.append(".prop-name { font-weight: 700; color: ").append(pdfAccent)
                        .append("; font-size: ").append(sectionPt).append("; margin: 10px 0 3px 0; }\n");
                sb.append(".prop-table { border-collapse: collapse; width: 100%; font-size: ").append(metaPt).append("; margin-bottom: 8px; }\n");
                sb.append(".prop-table th { background: #f5f5f5; text-align: left; padding: 4px 10px; border: 1px solid #e0e0e0; }\n");
                sb.append(".prop-table td { padding: 4px 10px; border: 1px solid #e0e0e0; }\n");

                // Footer
                sb.append(".page-footer { font-size: ").append(footerPt)
                        .append("; color: #aaa; text-align: center; padding-top: 10px; margin-top: auto; }\n");

                sb.append("</style></head><body>\n");

                // ============= COVER PAGE =============
                java.time.Instant earliest = null, latest = null;
                for (TableViewListItem item : items) {
                    java.time.Instant d = item.getLogEntry().getCreatedDate();
                    if (d != null) {
                        if (earliest == null || d.isBefore(earliest)) earliest = d;
                        if (latest == null || d.isAfter(latest)) latest = d;
                    }
                }
                sb.append("<div class='cover'>\n");
                sb.append("  <h1>").append(escapeHtml(pdfTitle)).append("</h1>\n");
                sb.append("  <div class='sub'>").append(escapeHtml(pdfSubtitle)).append("</div>\n");
                sb.append("  <div class='line'></div>\n");
                // Date range (not the search query)
                if (earliest != null && latest != null) {
                    String from = dateFmt.format(earliest);
                    String to   = dateFmt.format(latest);
                    if (from.equals(to)) {
                        sb.append("  <div class='date-range'>").append(from).append("</div>\n");
                    } else {
                        sb.append("  <div class='date-range'>").append(from)
                                .append("<br/>&mdash;<br/>").append(to).append("</div>\n");
                    }
                }
                sb.append("  <div class='stats'>").append(items.size()).append(" log entries</div>\n");
                sb.append("  <div class='generated'>Generated ").append(dateTimeFmt.format(java.time.Instant.now()))
                        .append("</div>\n");
                sb.append("</div>\n");

                // ============= TABLE OF CONTENTS =============
                if (showToc) {
                    sb.append("<div class='toc-page'>\n");
                    sb.append("  <div class='toc-title'>Table of Contents</div>\n");
                    String currentDay = "";
                    for (int i = 0; i < items.size(); i++) {
                        LogEntry tocEntry = items.get(i).getLogEntry();
                        // Day grouping header
                        if (tocEntry.getCreatedDate() != null) {
                            String day = dateFmt.format(tocEntry.getCreatedDate());
                            if (!day.equals(currentDay)) {
                                currentDay = day;
                                sb.append("  <div class='toc-day-header'>").append(day).append("</div>\n");
                            }
                        }
                        String tocTitle = tocEntry.getTitle() != null && !tocEntry.getTitle().isBlank()
                                ? tocEntry.getTitle() : "(no title)";
                        sb.append("  <div class='toc-entry'>\n");
                        sb.append("    <span class='toc-entry-title'>").append(i + 1).append(". ")
                                .append(escapeHtml(tocTitle)).append("</span>\n");
                        sb.append("    <span class='toc-entry-meta'>");
                        if (tocEntry.getCreatedDate() != null) {
                            sb.append(timeFmt.format(tocEntry.getCreatedDate()));
                        }
                        if (tocEntry.getOwner() != null) {
                            sb.append(" &mdash; ").append(escapeHtml(tocEntry.getOwner()));
                        }
                        sb.append("</span>\n");
                        sb.append("  </div>\n");
                    }
                    sb.append("</div>\n");
                }

                // ============= ONE ENTRY PER PAGE =============
                for (int i = 0; i < items.size(); i++) {
                    LogEntry entry = items.get(i).getLogEntry();
                    sb.append("<div class='entry-page'>\n");

                    // --- Header stripe ---
                    sb.append("<div class='entry-stripe'>\n");
                    String title = entry.getTitle() != null && !entry.getTitle().isBlank()
                            ? entry.getTitle() : "(no title)";
                    sb.append("  <div class='entry-title'>").append(escapeHtml(title)).append("</div>\n");
                    if (entry.getId() != null) {
                        sb.append("  <span class='entry-id'>Log #").append(entry.getId()).append("</span>\n");
                    }
                    sb.append("</div>\n");

                    // --- Metadata grid ---
                    sb.append("<div class='meta-grid'>\n");
                    if (entry.getCreatedDate() != null) {
                        sb.append("  <span class='meta-label'>Date</span><span class='meta-value'>")
                                .append(dateFmt.format(entry.getCreatedDate())).append(" &nbsp; ")
                                .append(timeFmt.format(entry.getCreatedDate())).append("</span>\n");
                    }
                    if (entry.getModifiedDate() != null && !entry.getModifiedDate().equals(entry.getCreatedDate())) {
                        sb.append("  <span class='meta-label'>Modified</span><span class='meta-value'>")
                                .append(dateTimeFmt.format(entry.getModifiedDate())).append("</span>\n");
                    }
                    if (entry.getOwner() != null) {
                        sb.append("  <span class='meta-label'>Author</span><span class='meta-value'>")
                                .append(escapeHtml(entry.getOwner())).append("</span>\n");
                    }
                    if (entry.getLevel() != null) {
                        sb.append("  <span class='meta-label'>Level</span><span class='meta-value'>")
                                .append("<span class='badge b-level'>").append(escapeHtml(entry.getLevel()))
                                .append("</span></span>\n");
                    }
                    if (entry.getLogbooks() != null && !entry.getLogbooks().isEmpty()) {
                        sb.append("  <span class='meta-label'>Logbooks</span><span class='meta-value'>");
                        entry.getLogbooks().forEach(lb ->
                                sb.append("<span class='badge b-logbook'>").append(escapeHtml(lb.getName())).append("</span>"));
                        sb.append("</span>\n");
                    }
                    if (entry.getTags() != null && !entry.getTags().isEmpty()) {
                        sb.append("  <span class='meta-label'>Tags</span><span class='meta-value'>");
                        entry.getTags().forEach(t ->
                                sb.append("<span class='badge b-tag'>").append(escapeHtml(t.getName())).append("</span>"));
                        sb.append("</span>\n");
                    }
                    sb.append("</div>\n");

                    // --- Body (Markdown → HTML) ---
                    String source = entry.getSource() != null ? entry.getSource() : entry.getDescription();
                    if (source != null && !source.isBlank()) {
                        sb.append("<div class='entry-body'>").append(htmlConverter.toHtml(source)).append("</div>\n");
                    }

                    // --- Attachment images ---
                    if (entry.getAttachments() != null && !entry.getAttachments().isEmpty()) {
                        java.util.List<org.phoebus.logbook.Attachment> imageAtts = new java.util.ArrayList<>();
                        java.util.List<org.phoebus.logbook.Attachment> fileAtts = new java.util.ArrayList<>();
                        for (org.phoebus.logbook.Attachment att : entry.getAttachments()) {
                            if (isImageAttachment(att)) {
                                imageAtts.add(att);
                            } else {
                                fileAtts.add(att);
                            }
                        }
                        if (!imageAtts.isEmpty()) {
                            sb.append("<div class='images-section'>\n");
                            sb.append("  <h3>Attachments</h3>\n");
                            for (org.phoebus.logbook.Attachment att : imageAtts) {
                                String key = entry.getId() + "/" + att.getName();
                                String dataUri = imageDataMap.get(key);
                                if (dataUri != null) {
                                    // Local temp file image (file:// URL)
                                    sb.append("  <img class='att-img' src='")
                                            .append(dataUri).append("'/>\n");
                                } else {
                                    // Fallback: URL reference (encode filename for spaces/special chars)
                                    String encName = att.getName() != null
                                            ? URLEncoder.encode(att.getName(), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20")
                                            : "image";
                                    String imgUrl = svcUrl + "/logs/attachments/" + entry.getId() + "/" + encName;
                                    sb.append("  <img class='att-img' src='")
                                            .append(escapeHtml(imgUrl)).append("'/>\n");
                                }
                                if (att.getName() != null) {
                                    sb.append("  <div class='att-caption'>").append(escapeHtml(att.getName()))
                                            .append("</div>\n");
                                }
                            }
                            sb.append("</div>\n");
                        }
                        if (!fileAtts.isEmpty()) {
                            sb.append("<div class='files-section'>\n");
                            sb.append("  <b>Other files:</b> ");
                            for (org.phoebus.logbook.Attachment att : fileAtts) {
                                sb.append("<span class='file-chip'>&#128206; ")
                                        .append(escapeHtml(att.getName() != null ? att.getName() : "file"))
                                        .append("</span>");
                            }
                            sb.append("\n</div>\n");
                        }
                    }

                    // --- Properties ---
                    if (entry.getProperties() != null && !entry.getProperties().isEmpty()) {
                        boolean hasVisibleProps = false;
                        for (org.phoebus.logbook.Property p : entry.getProperties()) {
                            if (!LogGroupProperty.NAME.equals(p.getName())) { hasVisibleProps = true; break; }
                        }
                        if (hasVisibleProps) {
                            sb.append("<div class='props-section'>\n");
                            sb.append("  <h3>Properties</h3>\n");
                            for (org.phoebus.logbook.Property prop : entry.getProperties()) {
                                if (LogGroupProperty.NAME.equals(prop.getName())) continue;
                                sb.append("  <div class='prop-name'>").append(escapeHtml(prop.getName()))
                                        .append("</div>\n");
                                Map<String, String> attrs = prop.getAttributes();
                                if (attrs != null && !attrs.isEmpty()) {
                                    sb.append("  <table class='prop-table'><tr><th>Attribute</th><th>Value</th></tr>\n");
                                    for (Map.Entry<String, String> attr : attrs.entrySet()) {
                                        sb.append("    <tr><td>").append(escapeHtml(attr.getKey()))
                                                .append("</td><td>").append(escapeHtml(
                                                        attr.getValue() != null ? attr.getValue() : ""))
                                                .append("</td></tr>\n");
                                    }
                                    sb.append("  </table>\n");
                                }
                            }
                            sb.append("</div>\n");
                        }
                    }

                    // --- Page footer ---
                    sb.append("<div class='page-footer'>").append(escapeHtml(pdfTitle))
                            .append(" &mdash; Entry ").append(i + 1)
                            .append(" of ").append(items.size())
                            .append(" &mdash; ").append(dateTimeFmt.format(java.time.Instant.now()))
                            .append("</div>\n");

                    sb.append("</div>\n"); // close entry-page
                }

                sb.append("</body></html>");

                final String html = sb.toString();

                // Write HTML to a temp file — WebView.loadContent() chokes on large
                // base64 strings; loading from a file URL is far more reliable.
                final Path tmpFile;
                try {
                    tmpFile = Files.createTempFile("olog_report_", ".html");
                    Files.writeString(tmpFile, html, java.nio.charset.StandardCharsets.UTF_8);
                    tmpFile.toFile().deleteOnExit();
                } catch (IOException ex) {
                    logger.log(Level.WARNING, "Cannot write temp HTML", ex);
                    Platform.runLater(() -> {
                        progressStage.close();
                        Alert alert = new Alert(AlertType.ERROR, "Cannot write temp file: " + ex.getMessage());
                        alert.setHeaderText("Report Generation Error");
                        alert.showAndWait();
                    });
                    return;
                }

                logger.log(Level.INFO,
                        "HTML report written to temp file ({0} chars, {1} KB)",
                        new Object[]{html.length(), tmpFile.toFile().length() / 1024});

                // Switch to UI thread for save dialog
                Platform.runLater(() -> {
                    progressStage.close();

                    // Offer to save the HTML report and open in system browser
                    // (WebView rendering is unreliable on busy consoles with many OPI displays)
                    javafx.stage.FileChooser fileChooser = new javafx.stage.FileChooser();
                    fileChooser.setTitle("Save Logbook Report");
                    fileChooser.getExtensionFilters().add(
                            new javafx.stage.FileChooser.ExtensionFilter("HTML files", "*.html"));
                    fileChooser.setInitialFileName("olog_report_"
                            + java.time.LocalDate.now().toString() + ".html");
                    java.io.File saveFile = fileChooser.showSaveDialog(tableView.getScene().getWindow());
                    if (saveFile != null) {
                        try {
                            Files.copy(tmpFile, saveFile.toPath(),
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                            // Copy temp images alongside the HTML so links remain valid
                            for (java.io.File imgFile : tempImageFiles) {
                                java.io.File dest = new java.io.File(saveFile.getParentFile(), imgFile.getName());
                                Files.copy(imgFile.toPath(), dest.toPath(),
                                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            }

                            // Rewrite image src paths to relative filenames
                            if (!tempImageFiles.isEmpty()) {
                                String saved = Files.readString(saveFile.toPath(), java.nio.charset.StandardCharsets.UTF_8);
                                for (java.io.File imgFile : tempImageFiles) {
                                    saved = saved.replace(imgFile.toURI().toString(), imgFile.getName());
                                }
                                Files.writeString(saveFile.toPath(), saved, java.nio.charset.StandardCharsets.UTF_8);
                            }

                            logger.log(Level.INFO, "Report saved to {0}", saveFile.getAbsolutePath());

                            // Try opening in system browser
                            try {
                                java.awt.Desktop.getDesktop().browse(saveFile.toURI());
                            } catch (Exception oe) {
                                // Fallback: show path to user
                                Alert info = new Alert(AlertType.INFORMATION,
                                        "Report saved to:\n" + saveFile.getAbsolutePath()
                                                + "\n\nOpen it in a web browser, then use Print \u2192 Save as PDF.");
                                info.setHeaderText("Report Saved");
                                info.showAndWait();
                            }
                        } catch (Exception ex) {
                            logger.log(Level.WARNING, "Failed to save report", ex);
                            Alert alert = new Alert(AlertType.ERROR,
                                    "Failed to save report: " + ex.getMessage());
                            alert.setHeaderText("Save Error");
                            alert.showAndWait();
                        }
                    }
                });

            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to generate HTML report", e);
                Platform.runLater(() -> {
                    progressStage.close();
                    Alert alert = new Alert(AlertType.ERROR, "Failed to generate report: " + e.getMessage());
                    alert.setHeaderText("Report Generation Error");
                    alert.showAndWait();
                });
            }
        });
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
