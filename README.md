# 时间紧迫？
直接下载打包好的 ：[Release：apipost-idea-plugin-patch-agent-xx.jar](https://github.com/intfoo/apipost-idea-plugin-patch-agent/releases)。跳到 [安装使用](#安装使用)章节查看用法。
# apipost-idea-plugin-patch-agent

一个 Java Agent，用于在运行时自动修复旧版 Apipost IDEA 插件中大量 `Read access is allowed from inside read-action only` 异常。

章节。
## 背景

 Apipost IDEA 官方插件（v1.0.23）在多处直接在 EDT（Event Dispatch Thread）上访问 PSI/模型 API，未包裹在 ReadAction 中，导致 IDEA 2025.x 频繁抛出：

```
com.intellij.openapi.diagnostic.RuntimeExceptionWithAttachments:
Read access is allowed from inside read-action only
```

在新版IDEA2026.1直接无法使用。
由于插件官方久无人维护，且未提供源码，无法从源码修复，因此采用 Java Agent 在类加载时动态织入 ReadAction 包装，无需修改插件 JAR 本身。

## 原理

```
IDEA 启动
   │
   ▼
JVM 加载 Agent（-javaagent 参数）
   │
   ▼
ReadActionTransformer.transform() 拦截目标类加载
   │
   ├─ 用 Javassist 将目标方法重命名为 _original_xxx
   │
   └─ 生成新方法体，通过 StaticHelper.runInReadAction()
      将原方法调用包裹在 ReadAction 中执行
```

## 项目结构

```
src/main/java/com/github/intfoo/agent/
├── AgentMain.java               # premain 入口，注册 Transformer
├── ReadActionTransformer.java   # ClassFileTransformer，拦截并织入目标方法
└── StaticHelper.java            # 运行时工具类，提供 ReadAction 包装执行
```

## 编译与打包

### 前置要求

- JDK 11+
- Maven 3.6+
- IntelliJ IDEA 本地安装（用于编译期依赖）

### 修改 pom.xml 中的 IDEA 路径

将以下依赖的 `systemPath` 改为本机 IDEA 安装路径：

```xml
<systemPath>D:/Program Files/JetBrains/IntelliJ IDEA 2025.3.1/lib/app.jar</systemPath>
```

### 打包

```bash
mvn clean package -DskipTests
```

产物为 `target/apipost-idea-plugin-patch-agent-1.0.jar`（带 `original-` 前缀的是备份，不用它）。

## 安装使用

找到 IDEA 配置目录下的 JVM 参数文件：

- Windows：`%APPDATA%\JetBrains\IntelliJIdea2025.x\idea64.exe.vmoptions`
- macOS：`~/Library/Application Support/JetBrains/IntelliJIdea2025.x/idea.vmoptions`
- Linux：`~/.config/JetBrains/IntelliJIdea2025.x/idea64.vmoptions`

在文件末尾添加一行：

```
-javaagent:C:/path/to/apipost-idea-plugin-patch-agent-1.0.jar
```

重启 IDEA 后生效。日志中出现以下输出说明 Agent 加载成功：

```
[ReadActionPatch] Agent loaded
[ReadActionPatch] patched: cn/apipost/restful/debug/RestServiceDetail#setApiService
[ReadActionPatch] patched: cn/apipost/restful/debug/CustomEditor#createDocument
...
```

## 新增修复目标

在 `ReadActionTransformer.java` 的静态块中添加一行即可：

```java
static {
    register("cn/apipost/restful/debug/RestServiceDetail",       "setApiService");
    register("cn/apipost/restful/debug/CustomEditor",            "createDocument");
    register("cn/apipost/restful/method/action/PropertiesHandler","findPsiFileInModule");
    // 新增：
    register("cn/apipost/restful/xxx/YourClass",                 "yourMethod");
}
```

### 如何找到需要修复的类

在 IDEA 日志（`idea.log`）中搜索：

```
Read access is allowed from inside read-action only
```

查看堆栈中 `cn.apipost` 开头的第一帧，即为需要注册的类和方法。

## 搜索类所在 JAR（PowerShell）

当编译报找不到某个 IDEA 内部类时，用以下脚本定位它在哪个 JAR：

```powershell
$className = "com/intellij/openapi/util/UserDataHolder.class"
$ideaLib   = "D:\Program Files\JetBrains\IntelliJ IDEA 2025.3.1\lib"

Get-ChildItem "$ideaLib\*.jar" | ForEach-Object {
    $jar    = $_.FullName
    $result = & jar tf $jar 2>$null | Where-Object { $_ -eq $className }
    if ($result) { Write-Host $jar }
}
```

找到后在 `pom.xml` 中添加对应的 `system` 依赖。

## 已知限制

- `StaticHelper.runInReadAction` 内部使用反射调用原方法，有轻微性能开销。对于调用频繁的热点方法，可在 `StaticHelper` 中为其单独编写具名静态方法以避免反射。
- `scope=system` 的 IDEA 依赖与本机安装路径绑定，换机器需修改 `pom.xml`。
- 仅针对 Apipost IDEA 插件 v1.0.23 和 IDEA 202６.1 测试，其他版本未验证。
