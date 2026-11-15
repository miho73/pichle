# Pichle MVP

Pleos 차량에서 드라이브스루 매장을 고르고, 이동·예상 대기시간을 비교한 뒤 메뉴를 준비해 전달하는 Android 앱 초안입니다. 현재 버전은 화면 흐름을 확인하기 위한 로컬 샘플 데이터로 동작합니다.

## 구현된 흐름

1. 주변 드라이브스루 목록과 이동시간·예상 대기시간 비교
2. 매장 메뉴 및 옵션 확인, 즐겨찾기
3. 주문 목록 확인과 옵션 메모
4. 제휴 매장 주문 전송 확인 흐름(데모 확인만 표시하며 실제 주문은 전송하지 않음)
5. 비제휴 매장의 주문 내용을 기기 TTS로 읽기
6. 선택한 매장을 기기 지도 앱으로 열기

매장·메뉴·시간은 예시 데이터지만, 화면은 로컬 `DemoPichleApi`를 거쳐 데이터를 받고 주문 접수 응답까지 돌려주는 형태로 동작합니다. 앱 상단의 연결 상태는 이 로컬 시연 API의 상태입니다. 인터넷 서버, 실제 차량 위치/Vehicle SDK, 매장 주문 시스템에는 연결하지 않습니다. 실제 서비스 연결 시 교체할 인터페이스는 [PichleApi.java](app/src/main/java/ooo/ionya/driveaway/PichleApi.java)입니다.

## API 연결 계약 초안

`PichleApi` 인터페이스를 HTTP 구현으로 바꾸면 됩니다. 서버는 아래 기능을 제공하면 화면 흐름과 연결할 수 있습니다.

| 기능 | 제안 계약 |
|---|---|
| 주변 매장/혼잡도 | `GET /v1/stores?areaId={coarseAreaId}` → 매장, 거리, 이동분, 예상 대기분, 전자주문 지원 여부 |
| 매장 메뉴 | `GET /v1/stores/{storeId}/menu` → 상품, 가격, 옵션 |
| 제휴 주문 | `POST /v1/orders` → 주문 ID, 접수상태, 픽업 안내 |
| 혼잡도용 방문 신호 | `POST /v1/visit-signals` → 매장 ID와 구간 체류/정차/주행 시간·거리 |

방문 신호는 사용자 ID, VIN, 정확한 좌표, 목적지, 전체 경로를 포함하지 않는 형태로 설계했습니다. 현재 `DemoPichleApi`는 네트워크를 사용하지 않고 고정 응답을 반환합니다. 차량/위치 신호를 실제로 수집할 때는 사용자의 동의, Pleos 권한 및 SDK 제공 여부를 확인하고 동의 범위에서만 전송해야 합니다. 혼잡도 계산과 차량 데이터 연동은 이 MVP에서 구현하지 않았습니다.

## 실행

JDK 21과 Android SDK Platform 37이 필요합니다. `local.properties`의 SDK 경로를 환경에 맞게 설정한 뒤 Android Studio에서 실행하거나 다음 명령으로 APK를 만듭니다.

```powershell
./gradlew.bat :app:assembleDebug
```

APK 경로: `app/build/outputs/apk/debug/app-debug.apk`

실제 차량 신호(NaviHelper 위치, 브레이크, 주행거리·시간, 즐겨찾기, 경로 정보), 혼잡도 서버, 지도 경로 시간, 제휴 매장 주문, 번역/음성 API는 별도 연결 작업입니다. TTS는 데모를 위해 Android 기본 음성 출력을 사용합니다. 차량 창문 제어, 결제, 무인 주문 전송은 MVP 범위에서 제외했습니다.
