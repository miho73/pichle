package ooo.ionya.driveaway;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/** Screen-based ordering flow. Vehicle operations stay in the dedicated SDK controllers. */
public class MainActivity extends AppCompatActivity {
    private static final int BG = Color.rgb(246, 247, 248), INK = Color.rgb(34, 34, 34);
    private static final int MUTED = Color.rgb(113, 116, 119), LINE = Color.rgb(231, 233, 235);
    private static final int MINT = Color.rgb(42, 193, 188), PALE = Color.rgb(232, 248, 246);
    private final PichleApi api = new LocalPichleApi();
    private final List<PichleApi.StoreSummary> stores = new ArrayList<>();
    private final List<PichleApi.MenuItem> menu = new ArrayList<>();
    private final List<CartItem> cart = new ArrayList<>();
    private final HashSet<String> favorites = new HashSet<>();
    private OrderFlow screen = OrderFlow.STORES;
    private PichleApi.StoreSummary selected;
    private PichleApi.MenuItem draftMenu;
    private PichleApi.OrderReceipt receipt;
    private OrderSpeechController speech;
    private PleosNavigation navigation;
    private PleosWindowController windows;
    private LinearLayout content, footer;
    private TextView voiceStatusView, navigationStatusView, windowStatusView;
    private Button speechPlay, speechStop;
    private EditText optionNote;
    private final ArrayList<String> draftOptions = new ArrayList<>();
    private int draftQuantity = 1;
    private String draftNote = "", category = "전체", method = "";
    private String voiceStatus = "음성 준비 중", navigationStatus = "", windowStatus = "";
    private boolean arrived, voiceDelivered, submitting, destroyed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        favorites.addAll(getPreferences(MODE_PRIVATE).getStringSet("favorites", Collections.emptySet()));
        try {
            stores.addAll(api.findNearbyStores("songdo"));
            stores.sort((a, b) -> Integer.compare(a.driveMinutes + a.waitMinutes, b.driveMinutes + b.waitMinutes));
        } catch (Exception error) { toast("매장 정보를 불러오지 못했습니다."); }
        restoreState(state);
        speech = new OrderSpeechController(this, status -> runOnUiThread(() -> {
            if (destroyed) return;
            voiceStatus = status;
            updateSpeechViews();
        }));
        navigation = new PleosNavigation(this, status -> runOnUiThread(() -> {
            if (destroyed) return;
            navigationStatus = status;
            if (navigationStatusView != null) navigationStatusView.setText(status);
        }));
        windows = new PleosWindowController(this, status -> runOnUiThread(() -> {
            if (destroyed) return;
            windowStatus = status;
            if (windowStatusView != null) windowStatusView.setText(status);
        }));
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { goBack(); }
        });
        render();
    }

    private void go(OrderFlow next) {
        saveDraftNote();
        if (screen == OrderFlow.VOICE && next != OrderFlow.VOICE && speech != null) speech.stop();
        screen = next;
        render();
    }

    private void render() {
        if (screen != OrderFlow.STORES && selected == null) screen = OrderFlow.STORES;
        switch (screen) {
            case MENU: showMenu(); break;
            case OPTIONS: if (draftMenu == null) go(OrderFlow.MENU); else showOptions(); break;
            case REVIEW: showReview(); break;
            case METHOD: showMethod(); break;
            case SEND: showSend(); break;
            case VOICE: showVoice(); break;
            case TRAVEL: showTravel(); break;
            case PICKUP: showPickup(); break;
            default: showStores();
        }
    }

    private void page(String title, String subtitle) {
        voiceStatusView = null; navigationStatusView = null; windowStatusView = null;
        speechPlay = null; speechStop = null; optionNote = null;
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        setContentView(root);
        LinearLayout appbar = row();
        appbar.setBackgroundColor(Color.WHITE);
        appbar.setPadding(dp(24), dp(8), dp(24), dp(8));
        if (screen != OrderFlow.STORES) {
            Button back = button("‹ 뒤로", false, this::goBack);
            appbar.addView(back, params(dp(104), dp(52)));
        }
        TextView brand = text("Pichle", 23, INK, true);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams brandLp = new LinearLayout.LayoutParams(0, dp(52), 1);
        brandLp.setMarginStart(screen == OrderFlow.STORES ? 0 : dp(18));
        appbar.addView(brand, brandLp);
        if (selected != null && screen == OrderFlow.STORES && !cart.isEmpty()) {
            Button resume = button(receipt == null ? "장바구니 " + count() : "접수 내역", false,
                    () -> go(receipt == null ? OrderFlow.REVIEW : OrderFlow.SEND));
            appbar.addView(resume, params(dp(160), dp(52)));
        }
        root.addView(appbar, params(-1, -2));
        View rule = new View(this); rule.setBackgroundColor(LINE); root.addView(rule, params(-1, dp(1)));
        LinearLayout heading = column();
        heading.setPadding(dp(28), dp(24), dp(28), dp(18));
        heading.addView(text(title, 30, INK, true));
        if (subtitle != null && !subtitle.isEmpty()) addText(heading, subtitle, 16, MUTED, false, 8);
        root.addView(heading, params(-1, -2));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        content = column(); content.setPadding(dp(28), 0, dp(28), dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        footer = column(); footer.setBackgroundColor(Color.WHITE);
        footer.setPadding(dp(24), dp(14), dp(24), dp(18));
        footer.setVisibility(View.GONE);
        root.addView(footer, params(-1, -2));
    }

    private void showStores() {
        page("송도동 주변 매장", "이동시간 + 예상 대기시간 순");
        LinearLayout filters = row();
        for (String filter : new String[]{"전체", "커피", "버거", "즐겨찾기"}) {
            Button chip = button(filter, filter.equals(category), () -> { category = filter; render(); });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(50), 1);
            cp.setMarginEnd(dp(8)); filters.addView(chip, cp);
        }
        content.addView(filters, params(-1, -2));
        List<PichleApi.StoreSummary> visible = new ArrayList<>();
        for (PichleApi.StoreSummary store : stores) {
            if (category.equals("즐겨찾기") && !favorites.contains(store.id)) continue;
            if (!category.equals("전체") && !category.equals("즐겨찾기") && !store.category.contains(category)) continue;
            visible.add(store);
        }
        if (visible.isEmpty()) {
            addText(content, category.equals("즐겨찾기") ? "즐겨찾기한 매장이 없습니다." : "표시할 매장이 없습니다.", 18, MUTED, false, 32);
            return;
        }
        LinearLayout group = itemGroup();
        for (PichleApi.StoreSummary store : visible) {
            LinearLayout card = card();
            LinearLayout title = row();
            title.addView(text(store.name, 22, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
            Button heart = button(favorites.contains(store.id) ? "♥" : "♡", false, () -> {
                if (!favorites.add(store.id)) favorites.remove(store.id);
                getPreferences(MODE_PRIVATE).edit().putStringSet("favorites", new HashSet<>(favorites)).apply();
                render();
            });
            heart.setContentDescription(store.name + " 즐겨찾기");
            title.addView(heart, params(dp(52), dp(52))); card.addView(title);
            addText(card, store.category + " · " + String.format(Locale.KOREA, "%.1fkm", store.distanceMeters / 1000.0), 16, MUTED, false, 8);
            addText(card, "총 " + (store.driveMinutes + store.waitMinutes) + "분", 30, INK, true, 26);
            addText(card, "이동 " + store.driveMinutes + "분 · 대기 " + store.waitMinutes + "분", 17, MUTED, false, 8);
            addText(card, store.electronicOrdering ? "전자 주문 가능" : "음성 주문", 15, Color.rgb(20, 125, 119), true, 18);
            Button choose = button("메뉴 보기", true, () -> chooseStore(store));
            add(card, choose, 24, dp(58));
            addGrouped(group, card);
        }
        add(content, group, 18, -2);
    }

    private void chooseStore(PichleApi.StoreSummary store) {
        if (selected != null && !selected.id.equals(store.id) && !cart.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("매장을 변경할까요?")
                    .setMessage("현재 장바구니의 메뉴가 삭제됩니다.")
                    .setNegativeButton("취소", null)
                    .setPositiveButton("변경", (dialog, which) -> selectStore(store)).show();
        } else selectStore(store);
    }

    private void selectStore(PichleApi.StoreSummary store) {
        if (selected == null || !selected.id.equals(store.id)) {
            cart.clear(); receipt = null; method = ""; arrived = false; voiceDelivered = false;
            navigationStatus = ""; windowStatus = "";
        }
        selected = store; loadMenu(); go(OrderFlow.MENU);
    }

    private void loadMenu() {
        menu.clear();
        try { if (selected != null) menu.addAll(api.getMenu(selected.id)); }
        catch (Exception error) { toast("메뉴를 불러오지 못했습니다."); }
    }

    private void showMenu() {
        page(selected.name, "이동 " + selected.driveMinutes + "분 · 대기 " + selected.waitMinutes + "분"
                + " · " + (selected.electronicOrdering ? "전자 주문 가능" : "음성 주문"));
        LinearLayout group = itemGroup();
        for (PichleApi.MenuItem item : menu) {
            LinearLayout card = card();
            card.addView(text(item.name, 23, INK, true));
            addText(card, money(item.priceKrw), 22, INK, true, 14);
            addText(card, item.optionSchema, 16, MUTED, false, 12);
            Button pick = button("선택", false, () -> {
                draftMenu = item; draftQuantity = 1; draftOptions.clear(); draftNote = ""; go(OrderFlow.OPTIONS);
            });
            add(card, pick, 24, dp(58)); addGrouped(group, card);
        }
        content.addView(group);
        footerAction(count() == 0 ? "메뉴를 담아주세요" : count() + "개 · " + money(total()) + "  장바구니 보기",
                true, count() > 0, () -> go(OrderFlow.REVIEW));
    }

    private void showOptions() {
        page(draftMenu.name, money(draftMenu.priceKrw));
        LinearLayout options = card(); options.addView(text("옵션", 21, INK, true));
        for (String raw : draftMenu.optionSchema.split(",")) {
            String choice = raw.trim(); if (choice.isEmpty()) continue;
            Button option = button((draftOptions.contains(choice) ? "✓  " : "") + choice, draftOptions.contains(choice), () -> { });
            option.setOnClickListener(view -> {
                if (!draftOptions.remove(choice)) draftOptions.add(choice);
                option.setText((draftOptions.contains(choice) ? "✓  " : "") + choice);
                styleButton(option, draftOptions.contains(choice));
            });
            add(options, option, 12, dp(54));
        }
        content.addView(options);
        LinearLayout quantity = card();
        LinearLayout quantityRow = row();
        quantityRow.addView(text("수량", 21, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        Button less = button("−", false, () -> { });
        TextView value = text(String.valueOf(draftQuantity), 23, INK, true);
        value.setGravity(Gravity.CENTER);
        Button more = button("+", false, () -> { });
        less.setContentDescription("수량 줄이기"); more.setContentDescription("수량 늘리기");
        quantityRow.addView(less, params(dp(64), dp(56)));
        quantityRow.addView(value, params(dp(72), dp(56)));
        quantityRow.addView(more, params(dp(64), dp(56)));
        less.setOnClickListener(view -> { if (draftQuantity > 1) draftQuantity--; value.setText(String.valueOf(draftQuantity)); updateAddFooter(); });
        more.setOnClickListener(view -> { if (draftQuantity < 20) draftQuantity++; value.setText(String.valueOf(draftQuantity)); updateAddFooter(); });
        quantity.addView(quantityRow); add(content, quantity, 14, -2);
        LinearLayout note = card(); note.addView(text("요청사항", 21, INK, true));
        optionNote = new EditText(this); optionNote.setText(draftNote); optionNote.setTextSize(18);
        optionNote.setHint("예: 얼음 적게"); optionNote.setTextColor(INK); optionNote.setHintTextColor(MUTED);
        optionNote.setSingleLine(false); optionNote.setMaxLines(3);
        optionNote.setPadding(dp(12), dp(12), dp(12), dp(12));
        add(note, optionNote, 12, dp(96)); add(content, note, 14, -2);
        updateAddFooter();
    }

    private void updateAddFooter() {
        footer.removeAllViews();
        footerAction(draftQuantity + "개 담기 · " + money(draftMenu.priceKrw * draftQuantity), true, true, () -> {
            saveDraftNote();
            String options = String.join(", ", draftOptions);
            if (!draftNote.trim().isEmpty()) options += (options.isEmpty() ? "" : ", ") + draftNote.trim();
            cart.add(new CartItem(draftMenu.id, draftMenu.name, draftMenu.priceKrw, draftQuantity, options));
            receipt = null; voiceDelivered = false;
            toast("장바구니에 담았습니다."); go(OrderFlow.MENU);
        });
    }

    private void showReview() {
        page("장바구니", selected.name);
        if (cart.isEmpty()) {
            addText(content, "담은 메뉴가 없습니다.", 21, MUTED, false, 20);
            footerAction("메뉴 보기", true, true, () -> go(OrderFlow.MENU)); return;
        }
        for (int i = 0; i < cart.size(); i++) {
            final int index = i;
            CartItem item = cart.get(i); LinearLayout card = card();
            LinearLayout heading = row();
            heading.addView(text(item.name + "  " + item.quantity + "개", 22, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
            if (receipt == null) {
                Button remove = button("삭제", false, () -> { cart.remove(index); voiceDelivered = false; render(); });
                heading.addView(remove, params(dp(84), dp(48)));
            }
            card.addView(heading);
            if (!item.options.isEmpty()) addText(card, item.options, 17, MUTED, false, 10);
            addText(card, money(item.price * item.quantity), 22, INK, true, 18);
            add(content, card, i == 0 ? 0 : 12, -2);
        }
        if (receipt == null) add(content, button("+ 메뉴 추가", false, () -> go(OrderFlow.MENU)), 16, dp(54));
        addTotal(content);
        footerAction(receipt != null ? "접수 내역 보기" : selected.electronicOrdering ? "주문 방법 선택" : "음성 주문 준비",
                true, true, () -> {
                    if (receipt != null) go(OrderFlow.SEND);
                    else if (selected.electronicOrdering) go(OrderFlow.METHOD);
                    else { method = "voice"; go(OrderFlow.VOICE); }
                });
    }

    private void showMethod() {
        if (cart.isEmpty()) { go(OrderFlow.REVIEW); return; }
        page("주문 방법", selected.name + " · " + count() + "개 · " + money(total()));
        LinearLayout group = itemGroup();
        if (selected.electronicOrdering) {
            LinearLayout electronic = card();
            electronic.addView(text("매장으로 전송", 28, INK, true));
            addText(electronic, "선택한 주문을 매장에 보냅니다.", 18, MUTED, false, 14);
            add(electronic, button("주문 전송 화면으로", true, () -> { method = "electronic"; go(OrderFlow.SEND); }), 32, dp(68));
            addGrouped(group, electronic);
        }
        LinearLayout voice = card(); voice.addView(text("음성으로 주문", 28, INK, true));
        addText(voice, "주문대에서 차량 스피커로 읽어줍니다.", 18, MUTED, false, 14);
        add(voice, button("TTS 재생 화면으로", !selected.electronicOrdering, () -> { method = "voice"; go(OrderFlow.VOICE); }), 32, dp(68));
        addGrouped(group, voice); content.addView(group);
    }

    private void showSend() {
        if (cart.isEmpty()) { go(OrderFlow.REVIEW); return; }
        page(receipt == null ? "주문 전송" : "주문 접수 완료", selected.name);
        if (receipt != null) {
            LinearLayout result = card();
            result.addView(text("접수 번호", 19, MUTED, false));
            addText(result, receipt.pickupCode, 52, INK, true, 12);
            addText(result, "수령 창구에서 접수 번호를 확인해주세요.", 18, MUTED, false, 18);
            content.addView(result);
            orderSummary(content); addTotal(content);
            footerAction("매장으로 이동", true, true, () -> go(OrderFlow.TRAVEL));
        } else {
            LinearLayout target = card();
            target.addView(text(selected.name + "에 보낼 주문", 23, INK, true)); content.addView(target);
            orderSummary(content); addTotal(content);
            if (submitting) addText(content, "주문 전송 중…", 20, MUTED, false, 20);
            footerAction(submitting ? "전송 중…" : money(total()) + "  주문 전송", true, !submitting, this::submitOrder);
        }
    }

    private void submitOrder() {
        if (submitting || receipt != null || cart.isEmpty() || !selected.electronicOrdering) return;
        submitting = true; render();
        new android.os.Handler(getMainLooper()).post(() -> {
            if (destroyed) return;
            try {
                ArrayList<String> ids = new ArrayList<>();
                for (CartItem item : cart) for (int i = 0; i < item.quantity; i++) ids.add(item.id);
                receipt = api.submitOrder(new PichleApi.OrderRequest(selected.id, ids, orderText()));
            } catch (Exception error) { toast("주문을 전송하지 못했습니다. 다시 시도해주세요."); }
            submitting = false;
            if (screen == OrderFlow.SEND) render();
        });
    }

    private void showVoice() {
        if (cart.isEmpty()) { go(OrderFlow.REVIEW); return; }
        page("음성 주문", selected.name + (arrived ? " · 주문대 도착" : " · 주문 문장 확인"));
        LinearLayout player = card();
        player.addView(text("주문 읽어주기", 28, INK, true));
        voiceStatusView = text(voiceStatus, 18, MUTED, false); add(player, voiceStatusView, 10, -2);
        LinearLayout controls = row();
        speechPlay = button("▶  주문 음성 재생", true, () -> speech.speak(orderText()));
        speechStop = button("■  중지", false, () -> speech.stop());
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(0, dp(80), 3); playLp.setMarginEnd(dp(12));
        controls.addView(speechPlay, playLp); controls.addView(speechStop, new LinearLayout.LayoutParams(0, dp(80), 1));
        add(player, controls, 24, -2); content.addView(player);
        LinearLayout phrase = card();
        phrase.addView(text("주문 내용", 19, MUTED, true));
        TextView spoken = addText(phrase, orderText(), 26, INK, true, 18);
        spoken.setLineSpacing(dp(8), 1); add(content, phrase, 16, -2);
        addText(content, "정차 후 재생해주세요.", 16, MUTED, false, 16);
        footerAction(arrived ? "주문 전달 완료 · 수령으로" : "매장으로 이동", false, true, () -> {
            if (arrived) { voiceDelivered = true; go(OrderFlow.PICKUP); }
            else go(OrderFlow.TRAVEL);
        });
        updateSpeechViews();
    }

    private void updateSpeechViews() {
        if (voiceStatusView != null) voiceStatusView.setText(voiceStatus);
        boolean busy = speech != null && speech.isBusy();
        if (speechPlay != null) {
            speechPlay.setText(busy ? "음성 재생 중…" : "▶  주문 음성 재생");
            speechPlay.setEnabled(!busy); speechPlay.setAlpha(busy ? 0.6f : 1f);
        }
        if (speechStop != null) { speechStop.setEnabled(busy); speechStop.setAlpha(busy ? 1f : 0.4f); }
    }

    private void showTravel() {
        page("매장으로 이동", selected.name);
        LinearLayout destination = card();
        destination.addView(text(selected.name, 27, INK, true));
        addText(destination, selected.address, 17, MUTED, false, 10);
        addText(destination, "이동 약 " + selected.driveMinutes + "분", 32, INK, true, 28);
        addText(destination, "매장 대기 약 " + selected.waitMinutes + "분", 18, MUTED, false, 10);
        add(destination, button("차량 내비게이션으로 길안내", true,
                () -> navigation.navigate(selected.latitude, selected.longitude, selected.name, selected.address)), 28, dp(72));
        navigationStatusView = text(navigationStatus, 17, MUTED, false);
        add(destination, navigationStatusView, 14, -2); content.addView(destination);
        if (receipt != null) {
            LinearLayout accepted = card();
            accepted.addView(text("접수 번호  " + receipt.pickupCode, 24, INK, true));
            addText(accepted, count() + "개 · " + money(total()), 17, MUTED, false, 10);
            add(content, accepted, 16, -2);
        } else {
            addText(content, "주문대에 도착하면 준비한 음성을 재생할 수 있습니다.", 18, MUTED, false, 20);
        }
        footerAction(receipt == null && !voiceDelivered ? "주문대 도착 · 음성 주문" : "매장 도착 · 수령하기", false, true, () -> {
            arrived = true;
            go(receipt == null && !voiceDelivered ? OrderFlow.VOICE : OrderFlow.PICKUP);
        });
    }

    private void showPickup() {
        if (cart.isEmpty()) { go(OrderFlow.STORES); return; }
        page("상품 수령", selected.name);
        if (receipt != null) {
            LinearLayout code = card(); code.addView(text("접수 번호", 18, MUTED, false));
            addText(code, receipt.pickupCode, 44, INK, true, 10); content.addView(code);
        }
        LinearLayout window = card();
        window.addView(text("운전석 창문", 24, INK, true));
        addText(window, "수령 창구에 정차한 후 내려주세요.", 18, MUTED, false, 12);
        add(window, button("운전석 창문 내리기", true, () -> windows.lowerDriverWindow()), 24, dp(76));
        windowStatusView = text(windowStatus, 17, MUTED, false); add(window, windowStatusView, 14, -2);
        add(content, window, receipt == null ? 0 : 16, -2);
        orderSummary(content);
        footerAction("수령 완료", false, true, () -> {
            cart.clear(); receipt = null; method = ""; voiceDelivered = false; arrived = false;
            toast("수령이 완료됐습니다."); go(OrderFlow.STORES);
        });
    }

    private void goBack() {
        switch (screen) {
            case MENU: go(OrderFlow.STORES); break;
            case OPTIONS: go(OrderFlow.MENU); break;
            case REVIEW: go(OrderFlow.MENU); break;
            case METHOD: go(OrderFlow.REVIEW); break;
            case SEND: go(receipt == null ? OrderFlow.METHOD : OrderFlow.REVIEW); break;
            case VOICE: go(arrived ? OrderFlow.TRAVEL : selected.electronicOrdering ? OrderFlow.METHOD : OrderFlow.REVIEW); break;
            case TRAVEL: go(receipt == null ? OrderFlow.VOICE : OrderFlow.SEND); break;
            case PICKUP: go(receipt == null ? OrderFlow.VOICE : OrderFlow.TRAVEL); break;
            default: moveTaskToBack(true);
        }
    }

    private String orderText() {
        ArrayList<String> phrases = new ArrayList<>();
        for (CartItem item : cart) phrases.add(item.name + " " + item.quantity + "개"
                + (item.options.isEmpty() ? "" : ", " + item.options));
        return "안녕하세요. " + String.join(". ", phrases) + ". 주문하겠습니다. 감사합니다.";
    }

    private void orderSummary(LinearLayout parent) {
        LinearLayout summary = card(); summary.addView(text("주문 내역", 20, INK, true));
        for (CartItem item : cart) {
            LinearLayout line = row();
            line.addView(text(item.name + " " + item.quantity + "개", 18, INK, false), new LinearLayout.LayoutParams(0, -2, 1));
            line.addView(text(money(item.price * item.quantity), 18, INK, true)); add(summary, line, 18, -2);
            if (!item.options.isEmpty()) addText(summary, item.options, 15, MUTED, false, 6);
        }
        add(parent, summary, 16, -2);
    }

    private void addTotal(LinearLayout parent) {
        LinearLayout total = card(), line = row();
        line.addView(text("합계", 22, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        line.addView(text(money(total()), 26, INK, true)); total.addView(line); add(parent, total, 16, -2);
    }

    private void footerAction(String label, boolean primary, boolean enabled, Runnable action) {
        footer.setVisibility(View.VISIBLE);
        Button button = button(label, primary, action); button.setTextSize(20);
        button.setEnabled(enabled); button.setAlpha(enabled ? 1f : 0.4f);
        footer.addView(button, params(-1, dp(66)));
    }

    private LinearLayout itemGroup() {
        LinearLayout group = column();
        if (getResources().getConfiguration().screenWidthDp >= 1000) group.setOrientation(LinearLayout.HORIZONTAL);
        return group;
    }

    private void addGrouped(LinearLayout parent, LinearLayout child) {
        LinearLayout.LayoutParams p = parent.getOrientation() == LinearLayout.HORIZONTAL
                ? new LinearLayout.LayoutParams(0, -2, 1) : params(-1, -2);
        if (parent.getChildCount() > 0) {
            if (parent.getOrientation() == LinearLayout.HORIZONTAL) p.setMarginStart(dp(16)); else p.topMargin = dp(14);
        }
        parent.addView(child, p);
    }

    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private LinearLayout card() {
        LinearLayout c = column(); c.setPadding(dp(24), dp(22), dp(24), dp(22));
        GradientDrawable background = fill(Color.WHITE, 12); background.setStroke(dp(1), LINE); c.setBackground(background); return c;
    }
    private Button button(String label, boolean primary, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setTextSize(18); b.setAllCaps(false); b.setTypeface(null, Typeface.BOLD);
        b.setPadding(dp(14), dp(6), dp(14), dp(6)); b.setMinWidth(0); b.setMinimumWidth(0); b.setElevation(0);
        styleButton(b, primary); b.setOnClickListener(view -> action.run()); return b;
    }
    private void styleButton(Button b, boolean primary) {
        b.setBackgroundTintList(null);
        GradientDrawable background = fill(primary ? MINT : Color.WHITE, 9);
        if (!primary) background.setStroke(dp(1), LINE);
        b.setBackground(background); b.setTextColor(primary ? Color.rgb(7, 45, 44) : INK);
    }
    private TextView text(String label, int size, int color, boolean bold) {
        TextView v = new TextView(this); v.setText(label); v.setTextSize(size); v.setTextColor(color);
        v.setIncludeFontPadding(false); if (bold) v.setTypeface(null, Typeface.BOLD); return v;
    }
    private TextView addText(LinearLayout parent, String label, int size, int color, boolean bold, int margin) {
        TextView v = text(label, size, color, bold); add(parent, v, margin, -2); return v;
    }
    private void add(LinearLayout parent, View child, int top, int height) {
        LinearLayout.LayoutParams p = params(-1, height); p.topMargin = dp(top); parent.addView(child, p);
    }
    private GradientDrawable fill(int color, int radius) { GradientDrawable b = new GradientDrawable(); b.setColor(color); b.setCornerRadius(dp(radius)); return b; }
    private LinearLayout.LayoutParams params(int width, int height) { return new LinearLayout.LayoutParams(width, height); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private String money(int value) { return String.format(Locale.KOREA, "%,d원", value); }
    private int total() { int n = 0; for (CartItem i : cart) n += i.price * i.quantity; return n; }
    private int count() { int n = 0; for (CartItem i : cart) n += i.quantity; return n; }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    private void saveDraftNote() { if (optionNote != null) draftNote = optionNote.getText().toString(); }

    private void restoreState(Bundle state) {
        if (state == null) return;
        try { screen = OrderFlow.valueOf(state.getString("page", "STORES")); } catch (IllegalArgumentException ignored) { }
        for (PichleApi.StoreSummary store : stores) if (store.id.equals(state.getString("storeId"))) selected = store;
        loadMenu();
        ArrayList<Bundle> savedCart = state.getParcelableArrayList("cart");
        if (selected != null && savedCart != null) for (Bundle item : savedCart) cart.add(CartItem.from(item));
        String orderId = state.getString("orderId");
        if (orderId != null && selected != null && !cart.isEmpty()) receipt = new PichleApi.OrderReceipt(orderId, "RECEIVED", state.getString("pickupCode", ""));
        method = state.getString("method", ""); category = state.getString("category", "전체");
        arrived = state.getBoolean("arrived"); voiceDelivered = state.getBoolean("voiceDelivered");
        for (PichleApi.MenuItem item : menu) if (item.id.equals(state.getString("draftId"))) draftMenu = item;
        draftQuantity = Math.max(1, Math.min(20, state.getInt("draftQuantity", 1)));
        draftNote = state.getString("draftNote", "");
        ArrayList<String> choices = state.getStringArrayList("draftOptions"); if (choices != null) draftOptions.addAll(choices);
        navigationStatus = state.getString("navigationStatus", ""); windowStatus = state.getString("windowStatus", "");
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        saveDraftNote(); state.putString("page", screen.name());
        if (selected != null) state.putString("storeId", selected.id);
        ArrayList<Bundle> items = new ArrayList<>(); for (CartItem item : cart) items.add(item.save());
        state.putParcelableArrayList("cart", items);
        if (receipt != null) { state.putString("orderId", receipt.orderId); state.putString("pickupCode", receipt.pickupCode); }
        state.putString("method", method); state.putString("category", category);
        state.putBoolean("arrived", arrived); state.putBoolean("voiceDelivered", voiceDelivered);
        if (draftMenu != null) state.putString("draftId", draftMenu.id);
        state.putInt("draftQuantity", draftQuantity); state.putString("draftNote", draftNote);
        state.putStringArrayList("draftOptions", new ArrayList<>(draftOptions));
        state.putString("navigationStatus", navigationStatus); state.putString("windowStatus", windowStatus);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (speech != null) speech.close();
        if (navigation != null) navigation.close();
        if (windows != null) windows.close();
        super.onDestroy();
    }

    private static final class CartItem {
        final String id, name, options; final int price, quantity;
        CartItem(String id, String name, int price, int quantity, String options) {
            this.id = id; this.name = name; this.price = price; this.quantity = quantity; this.options = options;
        }
        Bundle save() {
            Bundle b = new Bundle(); b.putString("id", id); b.putString("name", name); b.putString("options", options);
            b.putInt("price", price); b.putInt("quantity", quantity); return b;
        }
        static CartItem from(Bundle b) { return new CartItem(b.getString("id", ""), b.getString("name", ""),
                b.getInt("price"), Math.max(1, b.getInt("quantity", 1)), b.getString("options", "")); }
    }
}
