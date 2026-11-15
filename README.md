# Pichle MVP

Pleos 차량에서 드라이브스루 매장을 고르고, 이동·예상 대기시간을 비교한 뒤 메뉴를 준비해 전달하는 Android 앱 초안입니다. 현재 버전은 화면 흐름을 확인하기 위한 로컬 샘플 데이터로 동작합니다.

## 구현된 흐름

매장 목록 → 메뉴 → 옵션·수량 → 장바구니 → 주문 방법 → 주문 전송 또는 음성 주문 → 이동 → 수령 순서로 화면을 구분했습니다. 비제휴 매장은 장바구니에서 음성 주문 화면으로 이어집니다. 음성 재생·중지는 음성 주문 화면, 길안내는 이동 화면, 창문 제어는 수령 화면에서 제공합니다. 주요 동작은 화면 하단에 고정합니다.

1. 주변 드라이브스루 목록과 이동시간·예상 대기시간 비교
2. 매장 메뉴 및 옵션 확인, 즐겨찾기
3. 주문 목록 확인과 옵션 메모
4. 주문 확인, 접수 번호 보관, 픽업 확인
5. Pleos 자체 TTS로 주문 읽기 및 중지 (호환 기기에서는 Android 한국어 TTS로 전환)
6. NaviHelper를 통한 좌표 기반 길안내와 내비게이션 화면 열기
7. Vehicle SDK를 통한 운전석 창문 내리기

매장·메뉴·가격·대기·주문 접수 응답은 `LocalPichleApi`의 로컬 데이터입니다. 매장은 가상이며 송도 지역의 예시 목적지 좌표를 사용합니다. 해당 좌표는 실제 매장 진입로로 검증되지 않았습니다. 실제 서비스 연결 시 교체할 인터페이스는 [PichleApi.java](app/src/main/java/ooo/ionya/driveaway/PichleApi.java)입니다. 길안내·음성 출력·창문 제어는 실제 Pleos SDK를 호출합니다. 앱 화면에는 개발용 안내 문구를 표시하지 않습니다.

## API 연결 계약 초안

`PichleApi` 인터페이스를 HTTP 구현으로 바꾸면 됩니다. 서버는 아래 기능을 제공하면 화면 흐름과 연결할 수 있습니다.

| 기능 | 제안 계약 |
|---|---|
| 주변 매장/혼잡도 | `GET /v1/stores?areaId={coarseAreaId}` → 매장, 거리, 이동분, 예상 대기분, 전자주문 지원 여부 |
| 매장 메뉴 | `GET /v1/stores/{storeId}/menu` → 상품, 가격, 옵션 |
| 제휴 주문 | `POST /v1/orders` → 주문 ID, 접수상태, 픽업 안내 |
| 혼잡도용 방문 신호 | `POST /v1/visit-signals` → 매장 ID와 구간 체류/정차/주행 시간·거리 |

방문 신호는 사용자 ID, VIN, 정확한 좌표, 목적지, 전체 경로를 포함하지 않는 형태로 설계했습니다. 현재 `LocalPichleApi`는 네트워크를 사용하지 않고 고정 응답을 반환합니다. 혼잡도 계산과 차량 데이터 수집은 이 MVP에서 구현하지 않았습니다.

## 실행

AGP 9.4.0, Gradle 9.6.0과 Android SDK Platform 37을 사용합니다. Gradle 실행에는 JDK 17 이상이 필요합니다. `local.properties`의 SDK 경로를 환경에 맞게 설정한 뒤 Android Studio에서 Gradle 동기화 후 실행하거나 다음 명령으로 APK를 만듭니다.

```powershell
./gradlew.bat :app:assembleDebug
```

APK 경로: `app/build/outputs/apk/debug/app-debug.apk`

## Pleos 연동

- NaviHelper 3.0.5: `initialize` → 리스너 등록 → `requestRoute` → `requestNavigationScreen(Foreground)`; 완료·오류 콜백으로 결과 확인
- TextToSpeech 3.3.1: `Mode.ON_DEVICE`로 초기화해 별도 서버 인증 없이 차량 음성 출력을 요청. 실패하면 Android 한국어 TTS로 전환. 두 경로 모두 재생 시작·완료·실패를 화면에 표시
- Vehicle 2.3.2: 창문 위치 Capability에서 운전석(`ROW_1_LEFT`)의 쓰기 지원 및 최댓값을 확인한 후 `setWindowPosition` 호출. 같은 연결로 현재 위치를 최대 8초 재조회하며 최댓값 도달 시에만 내림 완료 표시

Pleos Connect 2.1.0 에뮬레이터에서는 음성 시작·완료와 실제 목적지 경로 안내를 확인했습니다. 창문은 네이티브 VHAL까지 쓰기 요청이 전달되고 성공 응답을 받았으나 현재 위치가 계속 0으로 조회되었습니다. 이 경우 앱은 완료로 표시하지 않고 위치 변경 미확인을 안내합니다. 실차 동작은 검증하지 않았습니다.

필요 권한: `TTS_SERVICE`, `NAVI_ROUTE_SEARCH`, `NAVI_CUSTOM_MAP`, `CAR_WINDOWS`, `CONTROL_CAR_WINDOWS` (모두 `pleos.car.permission.*`). 권한의 실제 허용과 차량별 지원 범위는 실행 플랫폼에 따릅니다. 창문 제어는 사용자가 버튼을 누를 때만 호출합니다.

API 문서: [NaviHelper 목적지](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/destinations), [Pleos TTS](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/TextToSpeech/intro), [창문 위치 제어](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/window/set-window-position).

브레이크·주행거리·지오펜스 기반 혼잡도 분석, 실시간 매장 주문, 결제, 다국어 번역은 별도 구현 범위입니다.
