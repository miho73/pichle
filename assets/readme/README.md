# README 시각 자료

| 파일 | 내용 | 출처 |
| --- | --- | --- |
| `proposal-intro.png` | 앱 한 줄 소개 원문 캡처 | 「참가신청서 - Pichle」 2쪽의 소개 상자 |
| `proposal-development.png` | 개발 계획 원문 캡처 | 같은 문서 6쪽의 개발 계획 |
| `service-flow.png` | 탐색·메뉴 준비·주문·이동·수령 흐름도 | 같은 문서 4~6쪽의 구상을 요약하고 현재 코드의 범위를 병기 |
| `mau-scenarios.png` | MAU 목표 시나리오 그래프 | 같은 문서 8쪽의 2,683 / 5,366 / 8,049명 목표 |

원문 캡처는 220dpi로 페이지를 렌더링한 뒤 해당 내용 영역만 잘랐습니다. 운영용 연락처나 서명 영역은 포함하지 않았습니다. 전체 PDF는 저장소에 복사하지 않았습니다.

흐름도와 그래프는 원본 PDF에 있던 그림을 캡처한 것이 아니라, 계획서 본문을 바탕으로 재구성한 자료입니다. 그래프는 실제 서비스 이용 실적이나 별도로 검증한 시장 통계가 아닙니다. 모든 PNG는 불투명 배경이어서 밝은 테마와 어두운 테마에서 동일하게 표시됩니다.

루트 README의 로고는 기존 `assets/icon-cutout.png`를 그대로 참조합니다.

## 재생성

Python의 Pillow·matplotlib와 Poppler의 `pdftoppm`이 필요합니다. macOS의 Apple SD Gothic Neo를 우선 사용하며, 다른 환경에서는 Noto Sans CJK KR이 필요합니다.

저장소 루트에서 실행합니다.

```bash
python3 assets/readme/generate_visuals.py '/path/to/proposal.pdf'
```

PDF 인수를 생략하면 흐름도와 MAU 그래프만 다시 만듭니다. PDF 인수를 제공하면 2쪽과 6쪽의 원문 캡처도 다시 만듭니다.
