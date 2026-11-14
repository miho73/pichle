# DriveAway · Vehicle SDK 전체 기능 테스트

[Pleos Vehicle SDK 설정 문서](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/project-setup)의 **Vehicle 2.3.2 전체 공개 API**를 호출하는 Android 테스트 앱입니다.
20개 도메인의 API 인터페이스를 직접 탐색하므로, 특정 도메인이나 예제로 범위를 제한하지 않습니다.

| 종류 | API 시그니처 수 | 테스트 기능 |
|---|---:|---|
| Capability | 168 | 접근 수준, 지원 영역, 값 범위, 설정 목록 조회 |
| Get | 109 | 현재 값, 상태, 차량 정보, 통계 조회 (`is`·`has` 포함) |
| Control | 56 | `set`·`move`·`select`에 영역 및 값 입력 후 호출 |
| Register | 92 | 영역별 또는 전역 리스너 등록, 실제 변경 이벤트 표시 |
| Unregister | 92 | 등록 시 사용한 영역과 동일한 리스너로 해제 |
| **전체** | **517** | 이전 버전 API와 콜백 오버로드 포함 |

도메인: Brake, CarInfo, Display, Door, DrivingMode, EvBattery, Fuel, Gear, HVAC,
Light, Odometer, Safety, Seat, SideMirror, SteeringWheel, Tire, Toll, TurnSignal, Window, Wiper.

이 앱의 범위는 링크의 Vehicle SDK입니다. NaviHelper·TTS·STT·FusedLocation은 별도 SDK입니다.

## 실행

JDK 21, Android SDK Platform 37이 필요합니다. 프로젝트의 Gradle Wrapper 9.6.0 / AGP 9.5.1을 사용합니다.
`local.properties`의 `sdk.dir`을 자신의 SDK 경로로 설정합니다.

```sh
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start --user current -n ooo.ionya.driveaway/.MainActivity
```

Android Studio에서도 `app`을 선택해 실행할 수 있습니다.
여러 기기를 연결했다면 `adb -s <SERIAL>`로 대상을 지정합니다.
차량 API의 실제 응답을 받으려면 Pleos Connect 에뮬레이터 또는 호환 차량 서비스가 필요합니다.

## 사용 방법

1. **연결**을 누릅니다. `initialize()` 이후 Display Capability를 조회해 서비스 응답을 확인합니다.
2. 도메인·API 종류 필터와 이름·권한 검색으로 원하는 API를 찾습니다.
3. API를 선택하면 실제 시그니처, 인자 이름, 요구 권한 및 현재 권한 상태가 표시됩니다.
4. **관련 지원 기능 조회**로 접근 수준과 지원 범위를 먼저 확인합니다. 차량별 지원 기능은 다릅니다.
5. 영역 인자는 상수를 여러 개 조합하거나 **Capability 응답의 지원 영역에서 선택**할 수 있습니다. 에뮬레이터의 DoorLock처럼 여러 문을 하나의 영역으로 묶어야 하는 API도 있습니다.
6. Boolean·enum은 선택하고, 정수·실수는 직접 입력합니다. SmartGlassZoneCommand 목록은 `ON, OFF, SKIP`처럼 쉼표로 구분해 입력합니다.
7. **API 실행 / 제어 호출 / 구독 등록**을 누르고 응답과 로그를 확인합니다. 제어는 버튼을 누를 때 실제로 실행됩니다.
8. 구독 해제 API에서는 기존 구독을 선택합니다. 아래 구독 목록에서도 하나씩 또는 전체를 해제할 수 있습니다.
9. **전체 지원 기능 검사**는 Capability 168개만 순차 실행합니다. Get·Control·Register를 자동으로 실행하지 않습니다. 중지하면 다음 호출부터 멈추며 이미 전송한 호출의 결과는 기록됩니다.
10. **결과 JSON 복사 / JSON 파일 저장**으로 API 시그니처, 인자, 권한, 결과, 시간 및 오류를 내보냅니다.

화면 로그는 최근 200개, 결과는 최근 5,000개를 보관합니다. 오래 구독한 경우 JSON의 `droppedResults`로 이전 결과가 보관 한도를 넘었는지 확인할 수 있습니다.

일회성 호출에는 10초 제한이 있으며, 시간 초과 후 도착한 콜백은 무시합니다.
`selectSeatMemory`처럼 성공 콜백이 없는 API는 실패 콜백 없이 시간이 지나도 성공으로 단정하지 않고 `SENT_NO_SUCCESS_CALLBACK`으로 기록합니다.
연결 해제 및 백그라운드 전환 시 구독과 Vehicle 리소스를 해제합니다. 파일 저장기를 열어도 백그라운드로 전환되므로 복귀 후 다시 연결합니다.

## 권한 및 지원 여부

배포 AAR의 `@RequiresPermission`을 추출하여 필요한 22개 권한을 선언했습니다. 제한된 CarInfo·Safety API에 사용되는 `PRIVATE_CAR_INFO`·`PRIVATE_CAR_SAFETY`도 목록에 포함됩니다. 실제 허용 여부는 플랫폼 정책에 따라 다르며, API별 화면과 실패 응답으로 확인합니다.
SDK에 API가 존재한다는 것과 차량이 그 기능을 지원한다는 것은 다릅니다. `NONE`, 빈 지원 목록, 미지원 영역, 권한 오류는 그대로 표시합니다.
제어 값은 Capability의 범위와 허용 값에 맞춰 입력합니다. 모든 제어를 한꺼번에 실행하는 버튼은 없습니다.

## 전체 목록 및 검증

- [생성된 전체 API 목록](docs/vehicle-sdk-api-inventory.md)
- `ApiCatalog`: SDK의 20개 도메인과 모든 공개 메서드를 런타임에 탐색하며, 메타데이터 누락 시 오류를 표시합니다.
- `ApiEngine`: Kotlin 콜백 및 SDK 리스너를 연결하고 세션·타임아웃·구독 수명을 관리합니다.
- `scripts/generate-sdk-catalog.py`: AAR에서 Kotlin 인자 이름, 시그니처, 요구 권한, deprecated 표시 및 전체 목록을 다시 생성합니다. `assembleDebug`로 의존성을 받은 뒤 실행합니다.
- R8 설정은 탐색 대상 API, 모델, enum, 리스너 및 제네릭 시그니처를 보존합니다.

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# 517개 API의 입력 타입, 메타데이터, 선언 권한, 등록/해제 대응 검사
adb shell am instrument --user current -w \
  -e class ooo.ionya.driveaway.VehicleSdkContractTest \
  ooo.ionya.driveaway.test/androidx.test.runner.AndroidJUnitRunner
# Pleos 에뮬레이터 전용 통합 검사 (실차에서는 실행하지 않음)
adb shell am instrument --user current -w -e vehicleIntegration true \
  -e class ooo.ionya.driveaway.VehicleSdkIntegrationTest \
  ooo.ionya.driveaway.test/androidx.test.runner.AndroidJUnitRunner
```

통합 검사는 Capability 168개와 Get 109개를 호출하고, 미지원·권한 오류를 포함한 모든 결과를 기록합니다.
대표 Control은 현재 문 잠금 값을 그대로 다시 설정하여 호출을 검증하고, 실제 리스너 초기 이벤트와 동일 리스너 해제를 확인합니다.
모든 제어 값의 조합이나 모든 구독의 변경 이벤트를 자동 검증하는 검사는 아닙니다.

### 확인한 결과

Pleos Connect v2.1.0 AVD / Vehicle Service 2.3.22에서 다음을 확인했습니다.

- Debug 빌드, 단위 테스트, Lint 통과 (Lint 오류 0개)
- 전체 517개 API의 입력 타입·권한·콜백 메타데이터·구독 해제 대응 검사 통과
- 전체 517개 API 입력 화면 렌더링 검사 통과
- Capability 168개 + Get 109개 실제 호출 완료: 정상 응답 203개, 플랫폼의 미지원 속성·영역 또는 일시적 사용 불가 오류 74개를 기록 (시간 초과 없음)
- 지원되는 문 영역으로 잠금 값 조회 → 같은 값 제어 → 구독 초기 이벤트 → 동일 리스너 해제 검증 통과

- 앱 화면의 **전체 지원 기능 검사** 버튼으로도 168/168 완료 확인: 성공 응답 129개, 플랫폼 오류 39개

원본 호출 결과는 `build/verification/all-read-api-results.json`과 `build/verification/control-subscription-results.json`에 저장했습니다.
