package ooo.ionya.driveaway;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pichle MVP: discover a drive-through, compare estimated time, prepare and relay an order. */
public class MainActivity extends AppCompatActivity {
    private static final int BG = Color.rgb(245, 247, 244);
    private static final int INK = Color.rgb(28, 39, 34);
    private static final int MUTED = Color.rgb(104, 117, 109);
    private static final int GREEN = Color.rgb(27, 117, 83);
    private static final int PALE = Color.rgb(228, 241, 233);
    private final List<Store> stores = new ArrayList<>();
    private final List<String> orderLines = new ArrayList<>();
    private final List<String> orderItemIds = new ArrayList<>();
    private final List<String> favorites = new ArrayList<>();
    private final PichleApi api = new DemoPichleApi();
    private LinearLayout content;
    private Store selected;
    private TextToSpeech tts;
    private boolean ttsReady;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        seedDemoStores();
        tts = new TextToSpeech(this, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) tts.setLanguage(Locale.KOREAN);
        });
        showHome();
    }

    private void seedDemoStores() {
        try {
            for (PichleApi.StoreSummary item : api.findNearbyStores("songdo-demo-area")) {
                List<Menu> menu = new ArrayList<>();
                for (PichleApi.MenuItem m : api.getMenu(item.id)) menu.add(new Menu(m.id, m.name, m.priceKrw, m.optionSchema));
                stores.add(new Store(item.id, item.name, item.category,
                        String.format(Locale.KOREA, "%.1f km", item.distanceMeters / 1000.0),
                        item.waitMinutes, item.driveMinutes, item.electronicOrdering, menu));
            }
            stores.sort((a, b) -> Integer.compare(a.wait + a.drive, b.wait + b.drive));
        } catch (Exception ignored) { }
    }

    private void base(String eyebrow, String title, String subtitle) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(24), dp(18), dp(24), dp(28));
        scroll.addView(page);
        setContentView(scroll);
        TextView brand = text("PICHLE   /   DRIVE-THRU, MADE EASIER", 12, GREEN, true);
        page.addView(brand);
        TextView crumb = text(eyebrow, 12, MUTED, true);
        LinearLayout.LayoutParams crumbLp = lp(-2, -2); crumbLp.topMargin = dp(18); page.addView(crumb, crumbLp);
        TextView heading = text(title, 29, INK, true);
        LinearLayout.LayoutParams titleLp = lp(-1, -2); titleLp.topMargin = dp(5); page.addView(heading, titleLp);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = text(subtitle, 14, MUTED, false);
            LinearLayout.LayoutParams subLp = lp(-1, -2); subLp.topMargin = dp(5); page.addView(sub, subLp);
        }
        content = page;
        View divider = new View(this); divider.setBackgroundColor(Color.rgb(220, 226, 221));
        LinearLayout.LayoutParams divLp = lp(-1, dp(1)); divLp.topMargin = dp(20); divLp.bottomMargin = dp(16); page.addView(divider, divLp);
    }

    private void showHome() {
        base("PICK YOUR STOP", "오늘은 어디 들를까요?", "이동시간과 예상 대기시간을 함께 보고 골라보세요.");
        LinearLayout searchCard = card();
        TextView location = text("●   Pichle 매장 정보 연결됨", 15, GREEN, true); searchCard.addView(location);
        TextView searchHint = text("혼잡도와 메뉴 정보 업데이트 · 방금", 13, MUTED, false);
        LinearLayout.LayoutParams hintLp = lp(-1, -2); hintLp.topMargin = dp(4); searchCard.addView(searchHint, hintLp);
        content.addView(searchCard);

        LinearLayout summary = new LinearLayout(this); summary.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams summaryLp = lp(-1, -2); summaryLp.topMargin = dp(20); content.addView(summary, summaryLp);
        summary.addView(metric("3", "주변 DT"), new LinearLayout.LayoutParams(0, -2, 1));
        summary.addView(metric("2–9분", "예상 대기"), new LinearLayout.LayoutParams(0, -2, 1));
        summary.addView(metric("데모", "정보 기준"), new LinearLayout.LayoutParams(0, -2, 1));

        section("추천 매장", "이동시간과 매장 대기를 합산해 짧은 순으로 보여드려요.");
        for (Store store : stores) addStoreCard(store);
        LinearLayout info = card(); info.setBackground(tint(PALE, 16));
        info.addView(text("혼잡도 예측", 15, GREEN, true));
        TextView note = text("주변 차량의 체류·정차 신호를 모아 매장 대기를 추정하는 기능입니다. 지금은 송도 지역 시연 데이터로 작동합니다.", 13, INK, false);
        LinearLayout.LayoutParams nlp = lp(-1, -2); nlp.topMargin = dp(7); info.addView(note, nlp);
        LinearLayout.LayoutParams ilp = lp(-1, -2); ilp.topMargin = dp(18); content.addView(info, ilp);
        TextView demo = text("시연 모드 · 매장/대기/주문 응답은 로컬 시연 API입니다.", 11, MUTED, false);
        LinearLayout.LayoutParams demoLp = lp(-1, -2); demoLp.topMargin = dp(12); content.addView(demo, demoLp);
    }

    private void addStoreCard(Store store) {
        LinearLayout box = card();
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(row, lp(-1, -2));
        TextView name = text(store.name, 17, INK, true);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView save = text(favorites.contains(store.name) ? "♥" : "♡", 24, GREEN, true);
        save.setPadding(dp(10), 0, 0, 0); save.setOnClickListener(v -> {
            if (favorites.contains(store.name)) favorites.remove(store.name); else favorites.add(store.name);
            showHome();
        }); row.addView(save);
        TextView type = text(store.type + "   ·   " + store.distance, 13, MUTED, false);
        LinearLayout.LayoutParams typeLp = lp(-1, -2); typeLp.topMargin = dp(5); box.addView(type, typeLp);
        LinearLayout stats = new LinearLayout(this); stats.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams statsLp = lp(-1, -2); statsLp.topMargin = dp(14); box.addView(stats, statsLp);
        TextView wait = pill("대기 약 " + store.wait + "분", PALE, GREEN);
        stats.addView(wait);
        TextView drive = text("  +  이동 " + store.drive + "분  =  총 " + (store.wait + store.drive) + "분", 13, INK, true);
        stats.addView(drive);
        Button open = button("매장과 메뉴 보기", true, () -> {
            if (selected != store) { orderLines.clear(); orderItemIds.clear(); }
            selected = store; showStore();
        });
        LinearLayout.LayoutParams bp = lp(-1, dp(48)); bp.topMargin = dp(14); box.addView(open, bp);
        LinearLayout.LayoutParams cardLp = lp(-1, -2); cardLp.topMargin = dp(10); content.addView(box, cardLp);
    }

    private void showStore() {
        if (selected == null) { showHome(); return; }
        base("STORE DETAILS", selected.name, selected.type + "  ·  " + selected.distance + "  ·  매장 정보 업데이트됨");
        LinearLayout estimate = card();
        estimate.addView(text("예상 총 소요시간", 14, MUTED, true));
        TextView total = text((selected.wait + selected.drive) + "분", 34, GREEN, true);
        LinearLayout.LayoutParams tlp = lp(-1, -2); tlp.topMargin = dp(3); estimate.addView(total, tlp);
        TextView parts = text("이동 " + selected.drive + "분   +   매장 대기 " + selected.wait + "분", 14, INK, false);
        LinearLayout.LayoutParams plp = lp(-1, -2); plp.topMargin = dp(4); estimate.addView(parts, plp);
        content.addView(estimate);
        section("메뉴 미리보기", "옵션과 요청사항은 주문 확인 단계에서 정리합니다.");
        for (Menu menu : selected.menu) {
            LinearLayout item = card();
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); item.addView(row);
            TextView m = text(menu.name, 16, INK, true); row.addView(m, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(text(String.format(Locale.KOREA, "%,d원", menu.price), 14, GREEN, true));
            TextView opt = text("옵션: " + menu.options, 12, MUTED, false);
            LinearLayout.LayoutParams olp = lp(-1, -2); olp.topMargin = dp(5); item.addView(opt, olp);
            Button add = button("담기", false, () -> addMenu(menu));
            LinearLayout.LayoutParams ap = lp(-1, dp(42)); ap.topMargin = dp(12); item.addView(add, ap);
            LinearLayout.LayoutParams cp = lp(-1, -2); cp.topMargin = dp(9); content.addView(item, cp);
        }
        section("주문 방식", "매장 연동 여부에 따라 전달 방법을 선택할 수 있어요.");
        Button order = button("주문 내용 확인 · " + orderLines.size() + "개", true, this::showOrder);
        LinearLayout.LayoutParams op = lp(-1, dp(52)); content.addView(order, op);
        Button nav = button("길안내 열기", false, this::openNavigation);
        LinearLayout.LayoutParams np = lp(-1, dp(48)); np.topMargin = dp(9); content.addView(nav, np);
        backButton("매장 목록으로", this::showHome);
    }

    private void addMenu(Menu menu) {
        final EditText option = new EditText(this);
        option.setHint("옵션 또는 요청사항 (예: 샷 추가, 얼음 적게)");
        option.setSingleLine(true);
        new AlertDialog.Builder(this).setTitle(menu.name + " 담기")
                .setMessage(String.format(Locale.KOREA, "%,d원 · " + menu.options, menu.price))
                .setView(option).setNegativeButton("취소", null)
                .setPositiveButton("담기", (d, w) -> {
                    String extra = option.getText().toString().trim();
                    orderItemIds.add(menu.id);
                    orderLines.add(menu.name + (extra.isEmpty() ? "" : " · " + extra) + " · " + String.format(Locale.KOREA, "%,d원", menu.price));
                    Toast.makeText(this, "주문 목록에 담았어요", Toast.LENGTH_SHORT).show();
                    showStore();
                }).show();
    }

    private void showOrder() {
        base("YOUR ORDER", "주문 내용을 확인해요", selected == null ? "메뉴를 담으면 이곳에서 확인할 수 있어요." : selected.name + " · 총 " + orderLines.size() + "개");
        if (orderLines.isEmpty()) {
            LinearLayout empty = card(); empty.addView(text("아직 담은 메뉴가 없어요.", 15, MUTED, false)); content.addView(empty);
        } else {
            for (int i = 0; i < orderLines.size(); i++) {
                final int index = i;
                LinearLayout line = card();
                LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); line.addView(row);
                TextView detail = text((i + 1) + "   " + orderLines.get(i), 14, INK, true); row.addView(detail, new LinearLayout.LayoutParams(0, -2, 1));
                TextView remove = text("삭제", 12, MUTED, true); remove.setOnClickListener(v -> { orderLines.remove(index); orderItemIds.remove(index); showOrder(); }); row.addView(remove);
                LinearLayout.LayoutParams lp = lp(-1, -2); lp.topMargin = dp(8); content.addView(line, lp);
            }
        }
        LinearLayout choice = card(); choice.addView(text("전달 방법", 16, INK, true));
        TextView p = text("제휴 매장: 서버 주문 전송   ·   비제휴 매장: TTS로 주문 읽기", 13, MUTED, false);
        LinearLayout.LayoutParams pLp = lp(-1, -2); pLp.topMargin = dp(7); choice.addView(p, pLp);
        LinearLayout.LayoutParams cLp = lp(-1, -2); cLp.topMargin = dp(18); content.addView(choice, cLp);
        if (selected != null && selected.partner) {
            Button partner = button("제휴 주문 전송 (데모)", true, () -> {
                if (orderLines.isEmpty()) { toast("먼저 메뉴를 선택해 주세요."); return; }
                new AlertDialog.Builder(this).setTitle("주문을 전송할까요?")
                        .setMessage("데모 화면에서는 실제 주문이나 결제가 진행되지 않습니다. API 연결 후 주문 확인 단계가 연결됩니다.")
                        .setNegativeButton("계속 수정", null).setPositiveButton("주문 접수", (d, w) -> submitDemoOrder()).show();
            });
            LinearLayout.LayoutParams bp = lp(-1, dp(52)); bp.topMargin = dp(14); content.addView(partner, bp);
        } else {
            TextView offline = text("이 매장은 전자 주문 비제휴 매장입니다. 선택한 주문을 TTS로 전달해 보세요.", 13, GREEN, true);
            LinearLayout.LayoutParams bp = lp(-1, -2); bp.topMargin = dp(14); content.addView(offline, bp);
        }
        Button speak = button("TTS 주문 듣기 · 한국어", false, this::speakOrder);
        LinearLayout.LayoutParams sp = lp(-1, dp(48)); sp.topMargin = dp(9); content.addView(speak, sp);
        TextView accessibility = text("직접 말하기 어려운 경우에도 선택한 주문 내용을 음성으로 전달할 수 있습니다. 실제 주문 전에는 정차 후 사용하세요.", 12, MUTED, false);
        LinearLayout.LayoutParams alp = lp(-1, -2); alp.topMargin = dp(11); content.addView(accessibility, alp);
        Button nav = button("선택한 매장으로 길안내", false, this::openNavigation);
        LinearLayout.LayoutParams np = lp(-1, dp(48)); np.topMargin = dp(17); content.addView(nav, np);
        Button arrived = button("매장 도착 · 픽업 안내 보기", false, this::showPickupGuide);
        LinearLayout.LayoutParams rp = lp(-1, dp(48)); rp.topMargin = dp(9); content.addView(arrived, rp);
        TextView demo = text("주문 접수와 픽업 상태는 시연 응답입니다. 실제 주문·결제는 되지 않습니다.", 11, MUTED, false);
        LinearLayout.LayoutParams demoLp = lp(-1, -2); demoLp.topMargin = dp(10); content.addView(demo, demoLp);
        backButton("메뉴로 돌아가기", () -> { if (selected == null) showHome(); else showStore(); });
    }

    private void speakOrder() {
        if (orderLines.isEmpty()) { toast("읽을 주문 내용이 없어요."); return; }
        String spoken = "안녕하세요. " + String.join(", 그리고 ", orderLines.stream().map(s -> s.replaceAll(" · [0-9,]+원$", "")).toArray(String[]::new)) + " 주문하겠습니다.";
        if (ttsReady) tts.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "pichle-order");
        else toast("이 기기에서 음성 출력을 준비 중이에요.");
    }

    private void submitDemoOrder() {
        try {
            PichleApi.OrderReceipt receipt = api.submitOrder(new PichleApi.OrderRequest(selected.id,
                    new ArrayList<>(orderItemIds), String.join(" / ", orderLines)));
            new AlertDialog.Builder(this).setTitle("주문이 접수됐어요")
                    .setMessage("접수 번호  " + receipt.pickupCode + "\n상태  준비 확인 중\n\n시연용 응답입니다. 실제 매장으로 주문이 전달되지는 않았습니다.")
                    .setPositiveButton("확인", (d, w) -> showOrder()).show();
        } catch (Exception error) { toast("주문 응답을 받지 못했어요. 다시 시도해 주세요."); }
    }

    private void showPickupGuide() {
        new AlertDialog.Builder(this).setTitle("픽업 안내")
                .setMessage("매장 도착 · 주문 내용을 직원에게 보여주세요.\n\n수령 안내가 확인되면 안전하게 정차한 상태에서 직접 창문을 조작해 주세요. 차량 창문은 앱이 자동으로 움직이지 않습니다.")
                .setPositiveButton("확인", null).show();
    }

    private void openNavigation() {
        if (selected == null) { toast("먼저 방문할 매장을 선택해 주세요."); return; }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(selected.name)));
            startActivity(intent);
        } catch (Exception ex) { toast("이 기기에서 길안내 앱을 찾지 못했어요."); }
    }

    private void backButton(String label, Runnable action) {
        Button back = button(label, false, action);
        LinearLayout.LayoutParams lp = lp(-1, dp(48)); lp.topMargin = dp(18); content.addView(back, lp);
    }

    private void section(String title, String subtitle) {
        TextView t = text(title, 18, INK, true);
        LinearLayout.LayoutParams tlp = lp(-1, -2); tlp.topMargin = dp(22); content.addView(t, tlp);
        if (subtitle != null) {
            TextView s = text(subtitle, 12, MUTED, false);
            LinearLayout.LayoutParams slp = lp(-1, -2); slp.topMargin = dp(4); content.addView(s, slp);
        }
    }

    private LinearLayout metric(String value, String label) {
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(value, 18, GREEN, true));
        TextView l = text(label, 11, MUTED, false); LinearLayout.LayoutParams lp = lp(-1, -2); lp.topMargin = dp(3); col.addView(l, lp);
        return col;
    }

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(15), dp(16), dp(15)); box.setBackground(tint(Color.WHITE, 16));
        return box;
    }

    private TextView pill(String label, int color, int foreground) {
        TextView view = text(label, 12, foreground, true); view.setPadding(dp(10), dp(6), dp(10), dp(6)); view.setBackground(tint(color, 24)); return view;
    }

    private Button button(String label, boolean primary, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(14); b.setTypeface(null, Typeface.BOLD);
        b.setTextColor(primary ? Color.WHITE : GREEN);
        b.setBackground(tint(primary ? GREEN : PALE, 12)); b.setElevation(0);
        b.setOnClickListener(v -> action.run()); return b;
    }

    private TextView text(String label, int size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(label); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD); t.setIncludeFontPadding(true); return t;
    }

    private GradientDrawable tint(int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }

    private LinearLayout.LayoutParams lp(int width, int height) { return new LinearLayout.LayoutParams(width, height); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    @Override protected void onDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }

    private static final class Store {
        final String id, name, type, distance; final int wait, drive; final boolean partner; final List<Menu> menu;
        Store(String id, String name, String type, String distance, int wait, int drive, boolean partner, List<Menu> menu) {
            this.id=id; this.name=name; this.type=type; this.distance=distance; this.wait=wait; this.drive=drive; this.partner=partner; this.menu=menu;
        }
    }
    private static final class Menu {
        final String id, name, options; final int price;
        Menu(String id, String name, int price, String options) { this.id=id; this.name=name; this.price=price; this.options=options; }
    }
}
