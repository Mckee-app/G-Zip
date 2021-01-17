import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Insets;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.StringJoiner;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.ProgressMonitor;
import javax.swing.SwingWorker;
import javax.swing.event.TableModelListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumnModel;
import javax.swing.table.TableModel;

public class GZipFrame extends JFrame {

    private JPanel pnl_root;
    private JTextField txt_workDir;
    private JComboBox<CheckableItem> cmb_extension;
    private JComboBox<CheckableItem> cmb_lastModifiedDate;
    private JButton btn_search;
    private JCheckBox chk_deleteSL;
    private JButton btn_fileCompression;
    private JTable tbl_fileList;
    private JCheckBox chk_allSL;
    private JLabel lbl_totalSize;

    /** SLのデフォルト値 */
    private static final boolean DEFAULT_SL = Boolean.TRUE;

    /** コンボボックス表示の区切り文字 */
    private static final String COMBO_BOX_SEPARATOR = ",";

    /** 設定ファイル名 */
    private static final String SETTING_PROPERTIES = "setting.properties";

    /** 設定ファイル */
    private static final Properties settings;

    /** 文字コード */
    private static final Charset CHARSET = StandardCharsets.UTF_8;

    static {
        settings = new Properties();
        try (Reader reader = Files.newBufferedReader(Paths.get(SETTING_PROPERTIES), CHARSET)) {
            settings.load(reader);
        } catch (NoSuchFileException e) {
            System.out.println(SETTING_PROPERTIES + " が見つかりませんでした。");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private enum PropKeys {
        WORK_DIR("TextField.workDir"),
        EXTENSION("ComboBox.extension"),
        LAST_MODIFIED_DATE("ComboBox.lastModifiedDate");

        String key;

        PropKeys(String key) {
            this.key = key;
        }

    }

    private enum Column {
        SL(0, "SL"),
        DIR(1, "ディレクトリ"),
        FILE_NAME(2, "ファイル名"),
        UPDATE_DATE(3, "更新日時"),
        SIZE(4, "サイズ");

        int columnNum;

        String columnName;

        Column(int columnNum, String columnName) {
            this.columnNum = columnNum;
            this.columnName = columnName;
        }

        public static Object[] getColumnNames() {
            Object[] ret = new Object[Column.values().length];
            for (int i = 0; i < Column.values().length; i++) {
                Column column = Column.values()[i];
                ret[i] = column.columnName;
            }
            return ret;
        }

    }

    private enum Extension {
        OK("ok"),
        NG("ng"),
        LOG("log"),
        TXT("txt"),
        // Windowsのbakも考慮すると危険なので不可に変更
//        BAK("bak"),
        CSV("csv");

        String extName;

        Extension(String extName) {
            this.extName = extName;
        }
    }

    private enum DateLastModified {
        TODAY(0, "今日"),
        ONE_YEAR_AGO(-12, "1年前"),
        TWO_YEARS_AGO(-24, "2年前"),
        THREE_YEARS_AGO(-36, "3年前"),
        FOUR_YEARS_AGO(-48, "4年前"),
        FIVE_YEARS_AGO(-60, "5年前");

        String dateName;

        int months;

        DateLastModified(int months, String dateName) {
            this.months = months;
            this.dateName = dateName;
        }

        public static DateLastModified getByDateName(String dateName) {
            for (DateLastModified dlm : DateLastModified.values()) {
                if (dlm.dateName.equals(dateName)) {
                    return dlm;
                }
            }
            throw new RuntimeException("該当する日付が見つかりません。(" + dateName + ")");
        }

        public DateLastModified next() {
            int i = this.ordinal() + 1;
            if (i < DateLastModified.values().length) {
                return DateLastModified.values()[i];
            }
            return null;
        }
    }

    private enum Size {
        B, KB, MB, GB, TB
    }

    private static class Chunk {

        private final int num;
        private final Path path;

        private Chunk(int num, Path path) {
            this.num = num;
            this.path = path;
        }
    }

    public GZipFrame() {
        $$$setupUI$$$();
        setTitle("G-Zip");

        // 作業ディレクトリの取得
        txt_workDir.setText(getWorkDir());

        // 「全SLチェック」初期値設定
        chk_allSL.setSelected(DEFAULT_SL);

        // 初期値:削除する
        chk_deleteSL.setSelected(Boolean.TRUE);

        // 「圧縮」ボタン押下時のロジック
        btn_fileCompression.addActionListener(e -> compressFile());

        // 「全SLチェック」入れる/外すのロジック
        chk_allSL.addActionListener(e -> setAllSl(chk_allSL.isSelected()));

        // テキストボックス内でEnter押下時のロジック
        txt_workDir.addActionListener(e -> {
            // 入力値を保存
            storeInput();

            // ファイルテーブル設定
            setFileTable();
        });

        // 「検索」ボタン押下時のロジック
        btn_search.addActionListener(e -> {
            // 入力値を保存
            storeInput();

            // ファイルテーブル設定
            setFileTable();
        });

        // ファイルテーブル設定
        setFileTable();
    }

    private void createUIComponents() {
        // 拡張子のフィルタ付きコンボボックスを生成
        createExtCmbBx();

        // 更新日のフィルタ付きコンボボックスを生成
        createDateLastModifiedCmbBx();
    }

    private void createExtCmbBx() {
        // 拡張子の取得
        List<String> extList = Stream.of(getExtension().split(COMBO_BOX_SEPARATOR))
            .collect(Collectors.toList());

        CheckableItem[] items = new CheckableItem[Extension.values().length];
        for (int i = 0; i < Extension.values().length; i++) {
            Extension ext = Extension.values()[i];
            items[i] = new CheckableItem(ext.extName, extList.contains(ext.extName));
        }
        cmb_extension = new CheckedComboBox<>(new DefaultComboBoxModel<>(items));
    }

    private void createDateLastModifiedCmbBx() {
        // 更新日の取得
        List<String> list = Stream.of(getLastModifiedDate().split(COMBO_BOX_SEPARATOR))
            .collect(Collectors.toList());

        CheckableItem[] items = new CheckableItem[DateLastModified.values().length];
        for (int i = 0; i < DateLastModified.values().length; i++) {
            DateLastModified dateLastModified = DateLastModified.values()[i];
            items[i] = new CheckableItem(dateLastModified.dateName,
                list.contains(dateLastModified.dateName));
        }
        cmb_lastModifiedDate = new CheckedComboBox<>(new DefaultComboBoxModel<>(items));
    }

    private void storeInput() {
        settings.setProperty(PropKeys.WORK_DIR.key, txt_workDir.getText());
        settings.setProperty(PropKeys.EXTENSION.key, getCmbBxText(cmb_extension));
        settings.setProperty(PropKeys.LAST_MODIFIED_DATE.key, getCmbBxText(cmb_lastModifiedDate));

        try (Writer writer = Files
            .newBufferedWriter(Paths.get(SETTING_PROPERTIES), CHARSET, StandardOpenOption.CREATE)) {
            settings.store(writer, null);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String getWorkDir() {
        return settings.getProperty(PropKeys.WORK_DIR.key, System.getProperty("user.dir"));
    }

    private String getExtension() {
        return settings.getProperty(PropKeys.EXTENSION.key, "");
    }

    private String getLastModifiedDate() {
        return settings.getProperty(PropKeys.LAST_MODIFIED_DATE.key, "");
    }

    private void setFileTable() {
        try {
            // 部品非活性
            setEnabledAll(false);

            // 一覧テーブルの構築
            createFileTable();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), e.getClass().getName(),
                JOptionPane.ERROR_MESSAGE);
        } finally {
            // 部品活性
            setEnabledAll(true);
        }
    }

    private void createFileTable() {

        String workDir = txt_workDir.getText();

        if (workDir.isEmpty()) {
            throw new RuntimeException("作業ディレクトリは入力必須です。");
        }

        ProgressMonitor monitor = new ProgressMonitor(
            this, "検索中...", "\n", 0, 100);
        monitor.setMillisToDecideToPopup(0);
        monitor.setMillisToPopup(0);
        monitor.setProgress(0);

        SwingWorker<List<Path>, Chunk> sw = new SwingWorker<List<Path>, Chunk>() {

            /** 処理が重たいバックグラウンド処理 */
            @Override
            protected List<Path> doInBackground() {
                setProgress(0);
                publish(new Chunk(0, null));

                // 作業ディレクトリ
                Path startPath = Paths.get(workDir);

                // 最大値の設定
                monitor.setMaximum(getFileCount(startPath));

                // ファイル郡の格納リスト
                List<Path> pathList;

                AtomicInteger atomicInteger = new AtomicInteger(0);
                try (Stream<Path> stream = Files.find(startPath, Integer.MAX_VALUE,
                    (path, basicFileAttributes) -> isMatched(path, atomicInteger))
                ) {
                    pathList = stream.collect(Collectors.toList());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

                return pathList;
            }

            /**
             * ファイルフィルター
             * @param path ファイルパス
             * @param atomicInteger インクリメント整数
             * @return 合致したらtrue
             */
            private boolean isMatched(Path path, AtomicInteger atomicInteger) {
                if (!Files.isRegularFile(path)) {
                    // 通常ファイルでない場合は対象外 ※フォルダ等
                    return false;
                }

                System.out
                    .println(path.getFileName() + " (" + Thread.currentThread().getName() + ")");

                int num = atomicInteger.incrementAndGet();

                int percentage = BigDecimal.valueOf(num)
                    .divide(BigDecimal.valueOf(monitor.getMaximum()), 2, RoundingMode.DOWN)
                    .multiply(BigDecimal.valueOf(100)).intValue();

                if (percentage == 100) {
                    // 100%だと進捗モニターが非表示となるため99%にする
                    percentage = 99;
                }

                setProgress(percentage);
                publish(new Chunk(num, path));

//                try {
//                    Thread.sleep(50);
//                } catch (InterruptedException e) {
//                    System.err.println(e.toString());
//                }

                return isMatchedExtension(path) && isMatchedDate(path);
            }

            /**
             * 拡張子フィルター
             * @param path ファイルパス
             * @return 合致したらtrue
             */
            private boolean isMatchedExtension(Path path) {
                String extensions = getCmbBxText(cmb_extension);
                if (!extensions.isEmpty()) {
                    for (String extension : extensions.split(COMBO_BOX_SEPARATOR)) {
                        if (path.getFileName().toString().matches("^.+\\.(?i)" + extension + "$")) {
                            return true;
                        }
                        if (Extension.LOG.extName.equals(extension)) {
                            // logファイルは「*.log.数値」も含める
                            if (path.getFileName().toString().matches("^.+\\.(?i)log\\.[0-9]+$")) {
                                return true;
                            }
                        }
                    }
                }
                return false;
            }

            /**
             * 更新日フィルター
             * @param path ファイルパス
             * @return 合致したらtrue
             */
            private boolean isMatchedDate(Path path) {
                LocalDateTime LastModifiedTime;
                try {
                    // 当該ファイルの最終更新日時取得
                    LastModifiedTime = LocalDateTime.ofInstant(
                        Files.getLastModifiedTime(path).toInstant(),
                        ZoneId.systemDefault());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

                // 現在日時の取得
                LocalDateTime currentTime = LocalDateTime.now();

                // 以下、最終更新日時と各特定日時との比較
                String text = getCmbBxText(cmb_lastModifiedDate);
                if (!text.isEmpty()) {
                    for (String dateName : text.split(COMBO_BOX_SEPARATOR)) {
                        DateLastModified dlm = DateLastModified.getByDateName(dateName);
                        if (LastModifiedTime.isBefore(currentTime.plusMonths(dlm.months))) {
                            DateLastModified dlm_next = dlm.next();
                            if (dlm_next != null) {
                                if (LastModifiedTime
                                    .isAfter(currentTime.plusMonths(dlm_next.months))) {
                                    return true;
                                }
                            } else {
                                return true;
                            }
                        }
                    }
                }
                return false;
            }

            /** 途中経過の表示 */
            @Override
            protected void process(List<Chunk> chunks) {
                chunks.forEach(chunk -> {
                    if (Objects.isNull(chunk.path)) {
                        return;
                    }
                    String message = chunk.num
                        + "/"
                        + monitor.getMaximum()
                        + ":"
                        + chunk.path.getFileName();
                    monitor.setNote(message);

                    System.out.println(message + " (" + Thread.currentThread().getName() + ")");
                });
            }

            /** 処理終了 */
            @Override
            protected void done() {
                try {
                    List<Path> pathList = get();
                    createFileTable(pathList);
                } catch (InterruptedException | ExecutionException e) {
                    throw new RuntimeException(e);
                } finally {
                    monitor.close();
                }
            }
        };
        sw.addPropertyChangeListener(evt -> {
            // プログレスの処理
            if ("progress".equals(evt.getPropertyName())) {
                monitor.setProgress((Integer) evt.getNewValue());
            }
        });
        sw.execute();
    }

    private int getFileCount(Path dir) {
        int count;
        try (Stream<Path> stream = Files.walk(dir)
            .filter(p -> !p.toFile().isDirectory())) {
            count = (int) stream.count();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return count;
    }

    private void setEnabledAll(boolean b) {
        txt_workDir.setEnabled(b);
        btn_search.setEnabled(b);
        btn_fileCompression.setEnabled(b);
    }

    private void createFileTable(List<Path> pathList) {
        // Model取得
        TableModel tableModel = getTableModel(pathList);

        // 合計サイズの設定
        setTotalSize(tableModel);

        // テーブルの設定
        tbl_fileList.setModel(tableModel);
        tbl_fileList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        tbl_fileList.getTableHeader().setReorderingAllowed(true);
        tbl_fileList.setRowSelectionAllowed(true);
        tbl_fileList.getModel().addTableModelListener(tableModelListener);

        // 数値フォーマット設定
        tbl_fileList.getColumnModel().getColumn(Column.SIZE.columnNum)
            .setCellRenderer(new DefaultTableCellRenderer() {
                {
                    setHorizontalAlignment(JLabel.RIGHT);
                }

                @Override
                public Component getTableCellRendererComponent(JTable table, Object obj,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                    if (obj instanceof Long) {
                        obj = showSize((Long) obj, Size.KB);
                    }
                    return super
                        .getTableCellRendererComponent(table, obj, isSelected, hasFocus, row,
                            column);
                }
            });

        // ファイルテーブルのカラム幅の設定
        TableColumnModel columnModel = tbl_fileList.getColumnModel();
        columnModel.getColumn(Column.SL.columnNum).setPreferredWidth(30);
        columnModel.getColumn(Column.DIR.columnNum).setPreferredWidth(500);
        columnModel.getColumn(Column.FILE_NAME.columnNum).setPreferredWidth(200);
        columnModel.getColumn(Column.UPDATE_DATE.columnNum).setPreferredWidth(120);
        columnModel.getColumn(Column.SIZE.columnNum).setPreferredWidth(100);
    }

    private TableModel getTableModel(List<Path> pathList) {
        // カラム名設定
        Object[] columnNames = Column.getColumnNames();

        // 日付フォーマッター
        SimpleDateFormat f = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN);

        // データ設定
        Object[][] tableData = new Object[pathList.size()][columnNames.length];
        for (int i = 0; i < pathList.size(); i++) {
            Path path = pathList.get(i);
            File file = path.toFile();

            // 初期値はチェックありに
            tableData[i][Column.SL.columnNum] = DEFAULT_SL;
            tableData[i][Column.DIR.columnNum] = file.getParent();
            tableData[i][Column.FILE_NAME.columnNum] = file.getName();
            tableData[i][Column.UPDATE_DATE.columnNum] = f.format(file.lastModified());
            tableData[i][Column.SIZE.columnNum] = file.length();
        }

        return new DefaultTableModel(tableData, columnNames) {
            @Override
            public Class<?> getColumnClass(int colIndex) {
                if (tableData.length == 0) {
                    return Object.class;
                }
                return getValueAt(0, colIndex).getClass();
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                return Column.SL.columnNum == column;
            }
        };
    }

    private TableModelListener tableModelListener = e -> {
        if (e.getColumn() == Column.SL.columnNum) {
            DefaultTableModel model = (DefaultTableModel) e.getSource();

            // 合計サイズの設定
            setTotalSize(model);
        }
    };

    private void setTotalSize(TableModel model) {
        BigDecimal bd = BigDecimal.ZERO;
        for (int i = 0; i < model.getRowCount(); i++) {
            Boolean sl = (Boolean) model.getValueAt(i, Column.SL.columnNum);
            if (sl) {
                Long size = (Long) model.getValueAt(i, Column.SIZE.columnNum);
                bd = bd.add(BigDecimal.valueOf(size));
            }
        }
        lbl_totalSize.setText(showSize(bd.longValue(), Size.MB));
    }

    private String getCmbBxText(JComboBox<CheckableItem> cmbBx) {
        StringJoiner sj = new StringJoiner(COMBO_BOX_SEPARATOR);
        for (int i = 0; i < cmbBx.getModel().getSize(); i++) {
            CheckableItem item = cmbBx.getModel().getElementAt(i);
            if (item.isSelected()) {
                sj.add(item.toString());
            }
        }
        return sj.toString();
    }

    /**
     * 全SL設定
     */
    private void setAllSl(Boolean sl) {
        // 一時的にリスナー削除
        tbl_fileList.getModel().removeTableModelListener(tableModelListener);

        for (int i = 0; i < tbl_fileList.getModel().getRowCount(); i++) {
            Boolean slTmp = (Boolean) tbl_fileList.getModel().getValueAt(i, Column.SL.columnNum);
            if (sl.booleanValue() != slTmp.booleanValue()) {
                tbl_fileList.getModel().setValueAt(sl, i, Column.SL.columnNum);
            }
        }

        // 合計サイズの設定
        setTotalSize(tbl_fileList.getModel());

        // リスナー再登録
        tbl_fileList.getModel().addTableModelListener(tableModelListener);
    }

    /**
     * 圧縮処理
     */
    private void compressFile() {
        List<Path> pathList = new ArrayList<>();
        for (int i = 0; i < tbl_fileList.getModel().getRowCount(); i++) {
            Boolean sl = (Boolean) tbl_fileList.getModel().getValueAt(i, Column.SL.columnNum);
            if (sl) {
                // チェックあり行の処理
                String dir = (String) tbl_fileList.getModel().getValueAt(i, Column.DIR.columnNum);
                String fileName = (String) tbl_fileList.getModel()
                    .getValueAt(i, Column.FILE_NAME.columnNum);

                Path path = Paths.get(dir, fileName);
                pathList.add(path);
            }
        }

        if (pathList.isEmpty()) {
            JOptionPane
                .showMessageDialog(this, "圧縮するファイルがありません", "圧縮エラー", JOptionPane.ERROR_MESSAGE);
            return;
        }

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Zip保存先を決める");
        fileChooser.setSelectedFile(new File(txt_workDir.getText(), getZipFileName()));
        fileChooser.setFileFilter(new FileNameExtensionFilter("*.zip", "zip"));

        File zipFile = null;
        int selected = fileChooser.showSaveDialog(this);
        if (selected == JFileChooser.APPROVE_OPTION) {
            zipFile = fileChooser.getSelectedFile();
        }
        if (zipFile == null) {
            return;
        }
        String pathStr = zipFile.getPath();
        if (!pathStr.endsWith(".zip")) {
            zipFile = new File(pathStr + ".zip");
        }

        zip(pathList, zipFile);

        if (chk_deleteSL.isSelected()) {
            // SL選択ファイル郡削除
            for (Path path : pathList) {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    JOptionPane.showMessageDialog(this, e.getMessage(), e.getClass().getName(),
                        JOptionPane.ERROR_MESSAGE);
                    throw new RuntimeException(e);
                }
            }
        }

        String message =
            "正常に圧縮が完了しました。" + "\nパス：" + zipFile.getPath() + "\nサイズ：" + showSize(zipFile.length(),
                Size.MB);
        JOptionPane.showMessageDialog(this, message, "圧縮完了", JOptionPane.INFORMATION_MESSAGE);

        if (chk_deleteSL.isSelected()) {
            // 最新の情報に設定
            setFileTable();
        }
    }

    private String showSize(Long len, Size size) {
        BigDecimal ret = BigDecimal.valueOf(len);
        for (int i = 0; i < size.ordinal(); i++) {
            if (size == Size.KB) {
                ret = ret.divide(BigDecimal.valueOf(1024), 0, RoundingMode.UP);
            } else {
                ret = ret.divide(BigDecimal.valueOf(1024), 2, RoundingMode.UP);
            }
        }

        DecimalFormat df = new DecimalFormat("###,##0.##");
        return df.format(ret).concat(size.name());
    }

    /**
     * Zipファイル名の取得
     *
     * @return Zipファイル名
     */
    @SuppressWarnings("SpellCheckingInspection")
    private String getZipFileName() {
        DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
        return f.format(LocalDateTime.now());
    }

    /**
     * 圧縮処理
     *
     * @param pathList 圧縮するファイル郡
     * @param zipFile  圧縮ファイル
     */
    private void zip(List<Path> pathList, File zipFile) {
        try (FileOutputStream fos = new FileOutputStream(
            zipFile); BufferedOutputStream bos = new BufferedOutputStream(
            fos); ZipOutputStream zos = new ZipOutputStream(bos, Charset.defaultCharset())) {
            String removeStr = txt_workDir.getText() + File.separator;
            for (Path path : pathList) {
                byte[] bytes = Files.readAllBytes(path);
                String entryName = path.toFile().getPath().replace(removeStr, "");
                ZipEntry zipEntry = new ZipEntry(entryName);
                zos.putNextEntry(zipEntry);
                zos.write(bytes);
            }
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), e.getClass().getName(),
                JOptionPane.ERROR_MESSAGE);
            throw new RuntimeException(e);
        }
    }

    /**
     * Method generated by IntelliJ IDEA GUI Designer >>> IMPORTANT!! <<< DO NOT edit this method OR
     * call it in your code!
     *
     * @noinspection ALL
     */
    private void $$$setupUI$$$() {
        createUIComponents();
        pnl_root = new JPanel();
        pnl_root.setLayout(new GridLayoutManager(3, 1, new Insets(10, 10, 10, 10), -1, -1));
        pnl_root.setBackground(new Color(-855310));
        pnl_root.setEnabled(true);
        pnl_root.setPreferredSize(new Dimension(800, 500));
        pnl_root.setVisible(true);
        final JPanel panel1 = new JPanel();
        panel1.setLayout(new GridLayoutManager(1, 5, new Insets(0, 0, 0, 0), -1, -1));
        pnl_root.add(panel1,
            new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER,
                GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null,
                null,
                null, 0, false));
        final JLabel label1 = new JLabel();
        label1.setText("作業ディレクトリ");
        panel1.add(label1,
            new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null,
                null, 0,
                false));
        txt_workDir = new JTextField();
        txt_workDir.setText("");
        panel1.add(txt_workDir, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_WEST,
            GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW,
            GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(350, -1), null, 0, false));
        final JLabel label2 = new JLabel();
        label2.setText("拡張子");
        panel1.add(label2,
            new GridConstraints(0, 3, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null,
                null, 0,
                false));
        panel1.add(cmb_extension, new GridConstraints(0, 4, 1, 1, GridConstraints.ANCHOR_WEST,
            GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_GROW,
            GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final JPanel panel2 = new JPanel();
        panel2.setLayout(new GridLayoutManager(1, 12, new Insets(0, 0, 0, 0), -1, -1));
        pnl_root.add(panel2,
            new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER,
                GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null,
                null,
                null, 0, false));
        chk_deleteSL = new JCheckBox();
        chk_deleteSL.setText("選択ファイル削除");
        panel2.add(chk_deleteSL,
            new GridConstraints(0, 10, 1, 1, GridConstraints.ANCHOR_CENTER,
                GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        btn_fileCompression = new JButton();
        btn_fileCompression.setHorizontalAlignment(0);
        btn_fileCompression.setText("圧縮");
        panel2.add(btn_fileCompression,
            new GridConstraints(0, 11, 1, 1, GridConstraints.ANCHOR_CENTER,
                GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        final JPanel panel3 = new JPanel();
        panel3.setLayout(new GridLayoutManager(2, 2, new Insets(0, 0, 0, 0), -1, -1));
        pnl_root.add(panel3,
            new GridConstraints(2, 0, 1, 1, GridConstraints.ANCHOR_CENTER,
                GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null,
                null,
                null, 0, false));
        final JPanel panel4 = new JPanel();
        panel4.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        panel3.add(panel4,
            new GridConstraints(0, 0, 1, 2, GridConstraints.ANCHOR_CENTER,
                GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null,
                null,
                null, 0, false));
        final JLabel label3 = new JLabel();
        label3.setText("ファイル一覧");
        panel4.add(label3,
            new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null,
                new Dimension(526, 16), null, 0, false));
        final JScrollPane scrollPane1 = new JScrollPane();
        panel3.add(scrollPane1,
            new GridConstraints(1, 0, 1, 2, GridConstraints.ANCHOR_CENTER,
                GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, null,
                null, null, 0, false));
        tbl_fileList = new JTable();
        tbl_fileList.setAutoCreateRowSorter(true);
        tbl_fileList.setAutoResizeMode(0);
        scrollPane1.setViewportView(tbl_fileList);
        label1.setLabelFor(txt_workDir);
        label2.setLabelFor(cmb_extension);
        label3.setLabelFor(scrollPane1);
    }

    public JComponent $$$getRootComponent$$$() {
        return pnl_root;
    }
}
