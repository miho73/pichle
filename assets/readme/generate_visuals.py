"""Regenerate README figures; optionally pass the original proposal PDF path.

Requires Pillow, matplotlib and (for PDF excerpts) Poppler's pdftoppm.
Usage: python3 assets/readme/generate_visuals.py '/path/to/proposal.pdf'
"""

from pathlib import Path
import subprocess
import sys
import tempfile

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib import font_manager
from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).resolve().parent
FONT = Path("/System/Library/Fonts/AppleSDGothicNeo.ttc")
if not FONT.exists():
    FONT = Path(font_manager.findfont("Noto Sans CJK KR", fallback_to_default=False))
INK, MUTED = "#163D35", "#526A64"
GREEN, PALE, LINE = "#008878", "#E9F5F0", "#D5E5DE"


def font(size):
    return ImageFont.truetype(str(FONT), size)


def text(draw, xy, value, size, color=INK, anchor=None):
    draw.text(xy, value, font=font(size), fill=color, anchor=anchor)


def service_flow():
    im = Image.new("RGB", (1800, 790), "#F6FAF7")
    d = ImageDraw.Draw(im)
    text(d, (70, 45), "차 안에서 이어지는 드라이브스루", 52)
    text(d, (72, 119), "계획서 기반 서비스 흐름 · 전자 주문과 TTS 주문의 공통 경험", 29, MUTED)

    def box(bounds, title, subtitle, highlight=False):
        x0, y0, x1, y1 = bounds
        d.rounded_rectangle(bounds, radius=24, fill=GREEN if highlight else "white", outline=LINE, width=2)
        color = "white" if highlight else INK
        text(d, ((x0+x1)/2, y0+47), title, 34, color, "mm")
        text(d, ((x0+x1)/2, y0+94), subtitle, 25, "#DDF5EA" if highlight else MUTED, "mm")

    def arrow(points):
        d.line(points, fill=GREEN, width=5, joint="curve")
        x, y = points[-1]
        d.polygon([(x,y), (x-14,y-9), (x-14,y+9)], fill=GREEN)

    box((70,310,390,450), "01  매장 탐색", "이동 + 예상 대기시간")
    box((460,310,780,450), "02  메뉴 준비", "메뉴 · 옵션 · 주문 확인")
    box((900,205,1270,345), "03  제휴 매장", "차내 키오스크 전자 주문", True)
    box((900,415,1270,555), "03  비제휴 매장", "준비한 문장을 TTS로 전달", True)
    box((1410,310,1730,450), "04  이동 · 수령", "길안내와 창문 제어 보조")
    arrow([(390,380),(460,380)])
    arrow([(780,380),(835,380),(835,275),(900,275)])
    arrow([(780,380),(835,380),(835,485),(900,485)])
    arrow([(1270,275),(1340,275),(1340,380),(1410,380)])
    arrow([(1270,485),(1340,485),(1340,380),(1410,380)])
    d.rounded_rectangle((70,625,1730,728), radius=20, fill=PALE)
    text(d, (105,650), "현재 MVP", 28, GREEN)
    text(d, (300,650), "매장·메뉴·대기시간·주문 응답은 로컬 샘플 데이터", 28)
    text(d, (300,689), "길안내 · 한국어 TTS · 버튼을 통한 운전석 창문 제어는 Pleos SDK 호출", 27, MUTED)
    im.save(OUT / "service-flow.png", optimize=True)


def mau_chart():
    font_manager.fontManager.addfont(str(FONT))
    plt.rcParams.update({"font.family": font_manager.FontProperties(fname=str(FONT)).get_name(),
                         "axes.unicode_minus": False, "font.size": 13})
    fig, ax = plt.subplots(figsize=(11, 6.3), dpi=170)
    fig.set_facecolor("#F6FAF7")
    ax.set_facecolor("#F6FAF7")
    fig.text(.08, .92, "월간 활성 이용자(MAU) 목표", fontsize=24, color=INK, weight="bold")
    fig.text(.08, .86, "계획서의 가정에 따른 시나리오 · 실제 이용 실적 아님", color=MUTED, fontsize=13)
    values = [2683, 5366, 8049]
    bars = ax.bar(range(3), values, width=.5, color=["#B3DCCD", "#57B6A0", GREEN], zorder=3)
    ax.set_ylim(0, 9500)
    ax.set_xticks(range(3), ["초기\n월간 이용 비율 10%", "성장\n월간 이용 비율 20%", "확대\n월간 이용 비율 30%"])
    ax.set_yticks([0, 2000, 4000, 6000, 8000], ["0", "2,000", "4,000", "6,000", "8,000"])
    ax.set_ylabel("월간 활성 이용자 (명)", color=MUTED)
    ax.grid(axis="y", color=LINE, linewidth=.8, zorder=0)
    ax.tick_params(axis="both", length=0, colors=MUTED, pad=10)
    for spine in ax.spines.values():
        spine.set_visible(False)
    for bar, value in zip(bars, values):
        ax.text(bar.get_x()+bar.get_width()/2, value+260, f"{value:,}명", ha="center", color=INK, fontsize=18, weight="bold")
    fig.text(.08, .075, "산정 가정: 55,893대 × 80% × 60% ≈ 잠재 고객군 26,829대", color=MUTED, fontsize=12)
    fig.text(.08, .035, "출처: 참가신청서 - Pichle, 8쪽. 차량 기반 추정치를 이용자 목표로 환산한 계획 수치.", color=MUTED, fontsize=10)
    fig.subplots_adjust(left=.12, right=.96, bottom=.23, top=.77)
    fig.savefig(OUT / "mau-scenarios.png", facecolor=fig.get_facecolor())
    plt.close(fig)


def pdf_excerpts(pdf):
    # Crop only project content; administrative contact details/signatures are excluded.
    crops = {2: (48, 229, 548, 281), 6: (48, 47, 548, 435)}
    names = {2: "proposal-intro.png", 6: "proposal-development.png"}
    with tempfile.TemporaryDirectory() as tmp:
        for page, crop in crops.items():
            prefix = str(Path(tmp) / "page")
            subprocess.run(["pdftoppm", "-f", str(page), "-l", str(page), "-singlefile", "-r", "220", "-png", str(pdf), prefix], check=True)
            im = Image.open(prefix + ".png").convert("RGB")
            scale = 220 / 72
            im.crop(tuple(round(v*scale) for v in crop)).save(OUT / names[page], optimize=True)


if __name__ == "__main__":
    service_flow()
    mau_chart()
    if len(sys.argv) > 1:
        pdf_excerpts(Path(sys.argv[1]))
