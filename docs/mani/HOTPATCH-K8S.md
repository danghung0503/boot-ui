# BootUI Hotpatch trên k8s — một file jar (mani fork)

Sửa **thân method** của class đang chạy trong pod, không restart, không build lại image. App chỉ thêm **một file jar** vào
`libs/` và **một dòng** `build.gradle` — không task Gradle, không sửa Dockerfile, không `-javaagent`, không manifest.
Panel và API: [features/java-agent.md § Hotpatch](../features/java-agent.md#hotpatch).

Luật cố định: image **không chứa source**; Dockerfile của service hai phần **JDK build → JRE chạy**; JRE không có javac.

## Jar gồm những gì

Module `bootui-hotpatch` của repo này build **hai jar**, chọn một:

| Jar | Dung lượng | Khác biệt |
|---|---|---|
| `bootui-hotpatch-<version>-lite.jar` | **~21,5 MB** | không có bộ nhận OTLP (`/bootui/api/otlp` — BootUI nhận trace OpenTelemetry từ app); mọi panel khác giữ nguyên |
| `bootui-hotpatch-<version>.jar` | ~24,3 MB | đầy đủ |

Đề xuất bản **lite** khi app không gửi trace OTLP vào BootUI. Thành phần (bản đầy đủ):

| Phần | Vì sao |
|---|---|
| BootUI starter + engine + UI | console `/bootui`, panel Hotpatch |
| **agent BootUI** (jar lồng, `META-INF/bootui/bootui-agent.jar`) | lúc khởi động BootUI **tự nạp** agent vào JVM bằng lệnh chẩn đoán `JVMTI.agent_load` qua MBean (`jdk.management`) — JRE không có `jdk.attach`. Không cần `-javaagent` |
| **javac** (`jdk.compiler` + `jdk.internal.opt`) của **JDK build jar** | JRE không có javac; phải là javac (ECJ sinh bytecode khác ⇒ JVM từ chối redefine). Agent mở các package `java.base` javac cần (`Instrumentation.redefineModule`) — không cần `Add-Exports` |
| Vineflower | dịch ngược class từ `app.jar` (image không có source) |
| archunit (3,7 MB — advisor Architecture, REST API, GraalVM, CRaC), commons-io/lang3, antlr4, HdrHistogram, byte-buddy — **đổi package** sang `io.github.jdubois.bootui.shaded.*` | thư viện ngoài BOM Spring Boot; đổi package để không đụng bản của app |
| protobuf + OTel proto/semconv (2,4 MB, đổi package) — **chỉ bản đầy đủ** | bộ nhận OTLP; thiếu thì controller + decoder không đăng ký (`@ConditionalOnClass`) |

Không gộp gì của Spring (spring-*, Boot, Jackson, Micrometer, Reactor, Netty, OTel API): app tự có, theo BOM của app.

**javac trong jar = phiên bản Java của JDK build jar** ⇒ build jar bằng **cùng phiên bản Java với image JRE** của app (vd 21).

## 0. Điều kiện

| Điều kiện | Cách kiểm |
|---|---|
| Spring Boot **4.x**, Spring MVC (BootUI 2.x không chạy Boot 3) | `build.gradle` |
| App có `spring-tx` (có sẵn khi dùng JDBC / JPA / R2DBC); app không DB: thêm `implementation 'org.springframework:spring-tx'` | `./gradlew dependencies --configuration runtimeClasspath \| grep spring-tx` |
| Kiểu chạy: `java -jar app.jar`, jar giải nén theo layer + `JarLauncher` (Dockerfile layered, Buildpacks), hoặc `-Djarmode=tools extract` + `java -jar app/app.jar` — **cả ba đã kiểm** | — |
| JRE có module `java.compiler`, `java.instrument`, `jdk.management`, `jdk.zipfs` | `docker run --rm --entrypoint java <jre-image> --list-modules \| grep -E 'java.compiler\|java.instrument\|jdk.management\|jdk.zipfs'` |
| JRE có `lib/libinstrument.so` | `docker run --rm --entrypoint sh <jre-image> -c 'ls $JAVA_HOME/lib/libinstrument.so'` |
| JVM không chạy với `-XX:-EnableDynamicAgentLoading` | `JAVA_TOOL_OPTIONS` / ENTRYPOINT |
| `/tmp` ghi được (agent ~5 MB + giải nén `BOOT-INF` ~ cỡ `app.jar` khi dùng Hotpatch lần đầu) | pod `readOnlyRootFilesystem` ⇒ `emptyDir` cho `/tmp` |

## 1. Build jar (một lần cho mỗi phiên bản Java)

Repo BootUI là project **Maven**, nhưng chỉ cần JDK: `./mvnw` là Maven Wrapper đi kèm, tự tải Maven. Service dùng jar vẫn là
**Gradle** — Maven chỉ dùng một lần để ra file jar. Mạng: Maven Central + nodejs.org (module UI tải Node), hoặc qua kho nội bộ —
`~/.m2/settings.xml` với `<mirror><mirrorOf>central</mirrorOf><url>https://<kho-noi-bo>/…</url></mirror>`; Node:
`-Dfrontend.download.root=<mirror Node>` nếu không ra được nodejs.org.

```bash
git clone -b mani/hotpatch https://github.com/danghung0503/boot-ui.git && cd boot-ui
# JDK cùng phiên bản với image JRE của app (vd 21); maven dùng JDK này để build và lấy javac
JAVA_HOME=/path/to/jdk-21 ./mvnw -B -pl bootui-hotpatch -am clean package -DskipTests
#   hoặc build bằng JDK bất kỳ (≥ 17), chỉ lấy javac từ JDK 21:  -Djavac.home=/path/to/jdk-21
ls bootui-hotpatch/target/bootui-hotpatch-*.jar     # <version>.jar và <version>-lite.jar
```
Luôn có `clean`: không có thì các lần build UI trước để lại file JS cũ (tên có mã băm) trong `target` và bị gộp vào jar (đã gặp:
jar 30 MB thay vì 24 MB). Cần mạng tới Maven Central (hoặc proxy nội bộ) và Node (module UI tự tải qua `frontend-maven-plugin`).

## 2. Gắn vào app

Chép jar vào `libs/` của service, thêm **một dòng** (Gradle):
```groovy
// build.gradle (Groovy DSL)
dependencies {
    implementation files('libs/bootui-hotpatch-2.2.0-mani.6-lite.jar')     // hoặc bản đầy đủ không có -lite
}
```
```kotlin
// build.gradle.kts (Kotlin DSL)
dependencies {
    implementation(files("libs/bootui-hotpatch-2.2.0-mani.6-lite.jar"))
}
```
`bootJar` của plugin Spring Boot tự đưa jar vào `BOOT-INF/lib` (đã kiểm với project Gradle). Commit file jar vào repo service
(hoặc Git LFS) để CI build được. Maven (`pom.xml`): `<scope>system</scope>` không vào fat jar — dùng `mvn install:install-file`
vào kho nội bộ rồi khai dependency bình thường.
Không đổi gì khác: Dockerfile chuẩn (JDK build → JRE chạy `app.jar`) giữ nguyên. BootUI **tắt** khi không có
`BOOTUI_ENABLED=ON` — không nạp agent, `/bootui` trả 404 (đã kiểm).

## 3. Bật ở môi trường (ConfigMap / env — cấu hình, không phải code)

| Biến | Giá trị | Vì sao |
|---|---|---|
| `BOOTUI_ENABLED` | `ON` | bật BootUI (và tự nạp agent) |
| `BOOTUI_ALLOW_NON_LOCALHOST` | `true` | nhận request qua ingress — **vẫn bắt token** |
| `BOOTUI_ALLOWED_HOSTS` | host ingress của app | chống DNS rebinding |
| `SERVER_TOMCAT_REMOTEIP_REMOTE_IP_HEADER` | `x-client-ip-not-trusted` | **bắt buộc**: Spring trên k8s tin `X-Forwarded-For`; header giả `127.0.0.1` thành loopback = bỏ qua token |
| `JAVA_TOOL_OPTIONS` *(tuỳ chọn)* | `-XX:+EnableDynamicAgentLoading` | tắt cảnh báo JVM "A Java agent has been loaded dynamically" |

Ingress riêng cho path `/bootui` với **sticky cookie** (mỗi pod có token + bản vá riêng). ingress-nginx:
```yaml
metadata:
  annotations:
    nginx.ingress.kubernetes.io/affinity: cookie
    nginx.ingress.kubernetes.io/session-cookie-name: bootui_pod
    nginx.ingress.kubernetes.io/session-cookie-path: /
spec:
  rules:
    - host: <host app>
      http:
        paths:
          - { path: /bootui, pathType: Prefix, backend: { service: { name: <svc>, port: { name: http } } } }
```
Bộ nhớ: +~300 MB (BootUI + javac khi biên dịch).

## 4. Dùng

1. Token: log pod khi khởi động in **một lần** `BootUI bearer token for non-local API access: <token>` (mỗi lần pod chạy lại
   là token mới).
2. Mở `https://<host app>/bootui` → nhập token → *Instrumentation → Hotpatch*.
3. Gõ tên class → mở (nhãn **decompiled**: dịch ngược từ jar, mất comment) → sửa thân method → *Compile* (`⌘S`) → nhập lý
   do → *Apply*. Lời gọi kế tiếp chạy code mới. *Revert* trả lại bytecode gốc.
4. API: header `Authorization: Bearer <token>`; `GET /bootui/api/hotpatch/classes?q=`, `GET …/source?class=`,
   `POST …/compile`, `POST …/patches` (`{className, source, reason}`), `DELETE …/patches/{className}`.

## 5. Kiểm sau khi áp

```bash
H=https://<host app>
curl -sk -o /dev/null -w '%{http_code}\n' $H/bootui/api/hotpatch                                  # 401 (chưa có token)
curl -sk -H 'X-Forwarded-For: 127.0.0.1' -o /dev/null -w '%{http_code}\n' $H/bootui/api/hotpatch  # 401 (không giả được)
curl -sk -H "Authorization: Bearer $TOKEN" $H/bootui/api/hotpatch
#  ⇒ "available":true, "compiler":"javac <version> (bundled)"
docker run --rm --entrypoint sh <image> -c 'find / -xdev -name "*.java" | wc -l'                    # 0 (không source)
```
Cách đọc classpath theo kiểu chạy (`ApplicationArchive`, `ApplicationClassPath`):

| Kiểu chạy | Class của app | Classpath cho javac |
|---|---|---|
| `java -jar app.jar` | `BOOT-INF/classes` trong jar, giải nén ra thư mục tạm | `BOOT-INF/lib` trong jar, giải nén |
| giải nén + `JarLauncher` | `BOOT-INF/classes/` (URL của class loader) | URL của class loader (`BOOT-INF/lib/*.jar`) |
| `-Djarmode=tools extract` | trong `app/app.jar` mỏng, giải nén package của app ra thư mục tạm | `Class-Path` trong manifest (`lib/*.jar`) |

Đã kiểm 2026-11-02: app Spring Boot 4.1 (web + actuator + spring-tx + **chỉ** jar này) trên JRE 21 jlink (không `jdk.compiler`,
không `jdk.attach`): agent tự nạp, `javac 21.0.10+7-LTS (bundled)`, Lombok / record / `switch` / lambda dùng `this` vá + revert
đúng, 4/4 class vá nguyên trạng; advisor Architecture (ArchUnit đổi package) quét 5 class / 41 rule; tắt `BOOTUI_ENABLED` ⇒ 404,
không agent. Bản lite: như trên, `/bootui/api/otlp` ⇒ 404, log không thiếu class.
Ba kiểu chạy (2026-11-03, project **Gradle**, `bootJar` layered, jar `mani.6-lite`): `java -jar`, layered + `JarLauncher`,
`-Djarmode=tools extract` — mỗi kiểu liệt kê class, dịch ngược, biên dịch, vá + revert đúng, 4/4 class, không lỗi thiếu class.

## 6. Giới hạn

- Chỉ đổi **thân method**: thêm/xoá method, field, lambda mới, đổi chữ ký ⇒ JVM từ chối. Hằng `static final` đã inline không đổi.
- **Một pod một lần vá**; pod restart / scale / deploy ⇒ mất. Nhiều replica: vá từng pod (xoá cookie `bootui_pod` để sang pod khác).
- Vá tạm xong **phải** commit bản sửa thật và deploy.
- **Ai đọc được log pod là có token = sửa được code đang chạy.** Không có audit tập trung (danh sách bản vá trong JVM + log app).
- Agent nạp động: JVM in cảnh báo; một phiên bản Java sau có thể chặn mặc định ⇒ khi đó cần `-XX:+EnableDynamicAgentLoading`.
- Jar gắn chặt một phiên bản Java (javac bên trong): đổi Java của image ⇒ build lại jar.
- Code dịch ngược: mất comment; số dòng stack trace theo class gốc. Interface sinh tự động có class phụ chưa nạp (`$1`) có thể bị
  agent từ chối.

## Cách khác: dependency Maven từng phần (mini PC)

Khi app lấy BootUI từ kho Maven (`implementation 'com.julien-dubois.bootui:bootui-spring-boot-starter:<version>'`) thì
không cần jar gộp nhưng phải tự đóng javac (task `bundledJavac`) và gắn agent — bản mẫu: service `kafka-consumer-example`
của monorepo infra (`build.gradle`, `Dockerfile`).
