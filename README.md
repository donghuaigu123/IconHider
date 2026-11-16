# 图标隐藏 (IconHider)

一个真正傻瓜式的 Android 应用图标隐藏工具：打开 App，看到所有已安装应用列表，**关掉开关 = 桌面图标隐藏，打开开关 = 图标恢复显示**，一键完成，无需 Root、无需任何复杂设置。

## 功能特点

- 📱 列出设备上全部已安装应用（含图标、名称、包名）
- 🙈 每个应用一枚开关：关闭即隐藏桌面图标，打开即恢复
- 🔍 顶部搜索框快速定位应用
- 🧠 无需 Root：基于 Android 系统 `PackageManager.setComponentEnabledSetting` 机制实现
- 📊 顶部实时统计「共 N 个应用，已隐藏 M 个」
- ✅ 自身应用不出现在列表中，避免误把自己藏起来找不到

## 使用方法

1. 下载 `apk/图标隐藏-v1.0.apk`（或 GitHub Release 中的附件）安装到手机
2. 安装时如提示「未知来源」，允许即可
3. 打开「图标隐藏」，在列表中找到想隐藏的应用
4. 关闭该应用右侧的开关 → 桌面图标立即消失；想恢复时打开开关即可

## 技术原理

应用在桌面显示图标，本质上是清单里声明的 launcher 入口组件（Activity 或 activity-alias）处于启用状态。本应用调用系统的：

```java
pm.setComponentEnabledSetting(componentName,
        COMPONENT_ENABLED_STATE_DISABLED,   // 隐藏
        DONT_KILL_APP);
```

即可禁用目标应用的桌面入口组件，桌面（Launcher）便不再展示其图标；恢复时切换为 `COMPONENT_ENABLED_STATE_ENABLED`。该机制为 Android 公开 API，无需 Root。应用声明了 `QUERY_ALL_PACKAGES` 权限以枚举全部应用（Android 11+ 可见性要求）。

## 构建方式

项目为**零依赖纯构建**（无 Gradle/AGP），仅需 Android build-tools 与 platform android.jar：

```bash
./build.sh
```

脚本流程：`aapt2 compile/link` → `javac` → `d8` → `zip` 打包 → `zipalign` → `apksigner` 签名。

- `minSdk 21`（Android 5.0+）
- `targetSdk 34`
- 签名密钥 `keystore.jks` 仅用于演示，正式发布请自行替换

## 项目结构

```
IconHider/
├── AndroidManifest.xml          # 清单：QUERY_ALL_PACKAGES、入口 Activity
├── build.sh                     # 一键构建脚本
├── src/com/workbuddy/iconhider/
│   └── MainActivity.java        # 全部业务逻辑（列表 / 开关 / 搜索）
├── res/
│   ├── layout/main.xml          # 主界面：搜索框 + 状态栏 + 列表
│   ├── layout/item.xml          # 列表项：图标 + 名称 + 开关
│   ├── values/strings.xml       # 字符串资源
│   └── mipmap/ic_launcher.png   # 应用图标
└── apk/图标隐藏-v1.0.apk        # 已签名成品
```

## 注意事项

- 部分厂商系统（如 MIUI 等）对「应用双开」「系统预装应用」的组件禁用可能做了限制，此类应用开关可能不生效或被系统自动恢复
- 隐藏的只是桌面图标，应用本体仍可通过系统设置中的应用列表或其他入口打开
- 若本应用被系统清理，已隐藏的图标不会自动恢复，重新打开本应用把开关打开即可

## 开源许可

MIT License
