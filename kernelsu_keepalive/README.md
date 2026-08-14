# RustDesk Keepalive (KernelSU)

开机后自动恢复 RustDesk 被控端的无障碍服务与屏幕录制权限。

## 安装

方式一(KernelSU 管理器,推荐):
1. 将 `module.prop` 与 `boot-completed.sh` 打包为 zip(顶层直接放两个文件)
2. KernelSU 管理器 → 模块 → 从 zip 安装 → 重启

方式二(adb,需要 root shell):
```bash
adb shell su -c 'mkdir -p /data/adb/modules/rustdesk_keepalive'
adb push module.prop boot-completed.sh /data/adb/modules/rustdesk_keepalive/
adb shell su -c 'restorecon -R /data/adb/modules/rustdesk_keepalive'
adb reboot
```

## 重要:授予 app root 权限(可选兜底)

app 内置了 root 自愈兜底(模块缺失时生效)。首次使用时需在
**KernelSU 管理器 → 超级用户**中,将 RustDesk 加入允许列表(允许 su),
否则 app 内自愈不会生效(模块本身不受影响)。

## 验证

重启后:
- 设置 → 无障碍 → RustDesk Input 应为开启状态(其他无障碍服务不受影响)
- 打开 RustDesk → 被控服务自动运行,屏幕录制已授权(无确认弹窗)

## 卸载

KernelSU 管理器移除模块,或删除 /data/adb/modules/rustdesk_keepalive 后重启。

## 说明

- 仅作用于主用户(工作资料/多用户不支持)
- 模块脚本会**追加**而不是覆盖无障碍服务列表,不会清掉其他无障碍服务
