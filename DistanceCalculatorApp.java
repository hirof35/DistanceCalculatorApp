package distanceCalculatorApp;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;

public class DistanceCalculatorApp extends JFrame {
    private JTextField txtAddr1, txtAddr2;
    private JLabel lblResult;
    private JTextArea txtHistory;
    private JButton btnCalc, btnShowMap;
    
    private final String HISTORY_FILE = "distance_history.txt";
    private double[] lastP1, lastP2; // 直近の座標(経度, 緯度)

    public DistanceCalculatorApp() {
        // ウィンドウ基本設定
        setTitle("Java 地図距離計算マスター");
        setSize(800, 500);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        // --- レイアウト構築 ---
        JPanel mainPanel = new JPanel(new BorderLayout(15, 15));
        mainPanel.setBorder(new EmptyBorder(20, 20, 20, 20));
        mainPanel.setBackground(new Color(240, 242, 245));

        // 左側：操作パネル
        JPanel controlPanel = new JPanel();
        controlPanel.setLayout(new BoxLayout(controlPanel, BoxLayout.Y_AXIS));
        controlPanel.setPreferredSize(new Dimension(320, 0));
        controlPanel.setOpaque(false);

        // 入力エリア
        addInputSection(controlPanel, "出発地:", txtAddr1 = new JTextField("東京都庁"));
        addInputSection(controlPanel, "目的地:", txtAddr2 = new JTextField("大阪府庁"));

        controlPanel.add(Box.createVerticalStrut(15));

        // ボタンエリア
        btnCalc = createStyledButton("距離を計算する", new Color(33, 150, 243));
        btnShowMap = createStyledButton("Googleマップで表示", new Color(76, 175, 80));
        btnShowMap.setEnabled(false);

        btnCalc.addActionListener(e -> startCalculation());
        btnShowMap.addActionListener(e -> openMap());

        controlPanel.add(btnCalc);
        controlPanel.add(Box.createVerticalStrut(10));
        controlPanel.add(btnShowMap);
        controlPanel.add(Box.createVerticalStrut(20));

        lblResult = new JLabel("結果: --- km", SwingConstants.CENTER);
        lblResult.setFont(new Font("SansSerif", Font.BOLD, 22));
        lblResult.setAlignmentX(Component.CENTER_ALIGNMENT);
        controlPanel.add(lblResult);

        // 右側：履歴パネル
        JPanel historyPanel = new JPanel(new BorderLayout());
        historyPanel.setBorder(BorderFactory.createTitledBorder("計算履歴 (自動保存)"));
        txtHistory = new JTextArea();
        txtHistory.setEditable(false);
        txtHistory.setFont(new Font("Monospaced", Font.PLAIN, 12));
        historyPanel.add(new JScrollPane(txtHistory), BorderLayout.CENTER);

        // 全体統合
        mainPanel.add(controlPanel, BorderLayout.WEST);
        mainPanel.add(historyPanel, BorderLayout.CENTER);
        add(mainPanel);

        // --- 初期化処理 ---
        loadHistory();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                saveHistory();
            }
        });
    }

    private void addInputSection(JPanel panel, String label, JTextField tf) {
        JLabel l = new JLabel(label);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        tf.setMaximumSize(new Dimension(Integer.MAX_VALUE, 35));
        panel.add(l);
        panel.add(tf);
        panel.add(Box.createVerticalStrut(5));
    }

    private JButton createStyledButton(String text, Color bg) {
        JButton btn = new JButton(text);
        btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 45));
        btn.setAlignmentX(Component.CENTER_ALIGNMENT);
        btn.setBackground(bg);
        btn.setForeground(Color.WHITE);
        btn.setFont(new Font("SansSerif", Font.BOLD, 14));
        btn.setFocusPainted(false);
        return btn;
    }

    // --- ロジック部 ---

    private void startCalculation() {
        String a1 = txtAddr1.getText().trim();
        String a2 = txtAddr2.getText().trim();
        if (a1.isEmpty() || a2.isEmpty()) return;

        btnCalc.setEnabled(false);
        btnShowMap.setEnabled(false);
        lblResult.setText("通信中...");

        new SwingWorker<Double, Void>() {
            @Override
            protected Double doInBackground() throws Exception {
                lastP1 = fetchCoordinates(a1);
                lastP2 = fetchCoordinates(a2);
                if (lastP1 == null || lastP2 == null) throw new Exception("住所が見つかりませんでした。");
                return calculateHaversine(lastP1[1], lastP1[0], lastP2[1], lastP2[0]);
            }

            @Override
            protected void done() {
                try {
                    double dist = get();
                    String res = String.format("%.2f km", dist);
                    lblResult.setText(res);
                    btnShowMap.setEnabled(true);
                    
                    String log = String.format("[%s] %s → %s : %s\n", 
                        LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM/dd HH:mm")), a1, a2, res);
                    txtHistory.append(log);
                } catch (Exception e) {
                    lblResult.setText("エラー");
                    JOptionPane.showMessageDialog(DistanceCalculatorApp.this, e.getMessage(), "エラー", JOptionPane.ERROR_MESSAGE);
                } finally {
                    btnCalc.setEnabled(true);
                }
            }
        }.execute();
    }

    private double[] fetchCoordinates(String address) throws Exception {
        // 1. 入力文字列のクリーニング（前後の空白削除など）
        String cleanAddress = address.trim();
        if (cleanAddress.isEmpty()) return null;

        // 2. URLエンコード
        String encoded = URLEncoder.encode(cleanAddress, StandardCharsets.UTF_8);
        String url = "https://msearch.gsi.go.jp/address-search/AddressSearch?q=" + encoded;

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();
        
        // タイムアウトを設定（ネットワークが不安定な時用）
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        String json = response.body();

        // 3. 検索結果が空（[]）でないかチェック
        if (json.equals("[]") || !json.contains("\"coordinates\"")) {
            throw new Exception("「" + address + "」が見つかりませんでした。都道府県名から入力してみてください。");
        }

        // 4. 座標の抽出（前回のロジック）
        String key = "\"coordinates\":[";
        int start = json.indexOf(key);
        int end = json.indexOf("]", start);
        String[] parts = json.substring(start + key.length(), end).split(",");
        
        return new double[]{ Double.parseDouble(parts[0]), Double.parseDouble(parts[1]) };
    }

    private double calculateHaversine(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2) * Math.sin(dLat/2) + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon/2) * Math.sin(dLon/2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
    }

    private void openMap() {
        try {
            String url = String.format("https://www.google.com/maps/dir/%f,%f/%f,%f", lastP1[1], lastP1[0], lastP2[1], lastP2[0]);
            Desktop.getDesktop().browse(new URI(url));
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void loadHistory() {
        try {
            Path p = Paths.get(HISTORY_FILE);
            if (Files.exists(p)) txtHistory.setText(Files.readString(p, StandardCharsets.UTF_8));
        } catch (IOException e) { }
    }

    private void saveHistory() {
        try { Files.writeString(Paths.get(HISTORY_FILE), txtHistory.getText(), StandardCharsets.UTF_8); } 
        catch (IOException e) { }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new DistanceCalculatorApp().setVisible(true));
    }
}
