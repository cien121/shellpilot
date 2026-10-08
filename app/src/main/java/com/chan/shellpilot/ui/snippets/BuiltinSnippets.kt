package com.chan.shellpilot.ui.snippets

/** 内置命令模板：不可编辑、不可删除，随 App 发布。 */
data class BuiltinTemplate(
    val title: String,
    val description: String,
    val command: String,
    /** 系统分类：见 [BuiltinSnippets.OS_DEBIAN] / [BuiltinSnippets.OS_RHEL] */
    val os: String,
)

/** 内置模板库（仿 LobiShell 模板区）。用户自定义片段走 Room，两类分开展示。 */
object BuiltinSnippets {
    const val OS_DEBIAN = "Ubuntu / Debian"
    const val OS_RHEL = "Rocky / RHEL / Alma"

    val all: List<BuiltinTemplate> = listOf(
        // ---------- Ubuntu / Debian ----------
        BuiltinTemplate(
            title = "系统更新",
            description = "更新软件包索引，升级全部软件包并清理",
            command = "apt update && apt upgrade -y && apt autoremove -y && apt autoclean",
            os = OS_DEBIAN,
        ),
        BuiltinTemplate(
            title = "安装常用工具",
            description = "curl / wget / git / vim / htop 等常用工具",
            command = "apt install -y curl wget git vim htop net-tools unzip tree ncdu",
            os = OS_DEBIAN,
        ),
        BuiltinTemplate(
            title = "查找已安装软件包",
            description = "按名称搜索已安装的软件包（末尾可补关键词）",
            command = "dpkg -l | grep -i ",
            os = OS_DEBIAN,
        ),
        BuiltinTemplate(
            title = "重启 nginx",
            description = "重启 nginx 服务并查看状态",
            command = "systemctl restart nginx && systemctl status nginx",
            os = OS_DEBIAN,
        ),
        BuiltinTemplate(
            title = "重启 xray",
            description = "重启 xray 服务并查看状态",
            command = "systemctl restart xray && systemctl status xray",
            os = OS_DEBIAN,
        ),
        // ---------- Rocky / RHEL / Alma ----------
        BuiltinTemplate(
            title = "系统更新",
            description = "升级全部软件包并清理缓存",
            command = "dnf update -y && dnf autoremove -y && dnf clean all",
            os = OS_RHEL,
        ),
        BuiltinTemplate(
            title = "安装常用工具",
            description = "curl / wget / git / vim / htop 等常用工具",
            command = "dnf install -y curl wget git vim htop net-tools unzip tree",
            os = OS_RHEL,
        ),
        BuiltinTemplate(
            title = "查找已安装软件包",
            description = "按名称搜索已安装的 RPM 包（末尾可补关键词）",
            command = "rpm -qa | grep -i ",
            os = OS_RHEL,
        ),
        BuiltinTemplate(
            title = "重启 nginx",
            description = "重启 nginx 服务并查看状态",
            command = "systemctl restart nginx && systemctl status nginx",
            os = OS_RHEL,
        ),
        BuiltinTemplate(
            title = "重启 xray",
            description = "重启 xray 服务并查看状态",
            command = "systemctl restart xray && systemctl status xray",
            os = OS_RHEL,
        ),
    )
}
