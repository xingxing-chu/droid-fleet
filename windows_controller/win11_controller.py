# -*- coding: utf-8 -*-
"""
Windows 11 多安卓设备远程控制与批量管理终端 (Python GUI)
专为 Windows 11 设计的现代化受控设备批量管理与历史审计客户端

特性：
1. 设备自动持久化记忆 (无需每次打开重复添加，自动加载 remote_devices.json)
2. 完整历史记录与审计日志 (controller_history.json 与 controller_activity.log)
3. 历史指令一键重放 (Re-run past commands directly from history)
4. 多设备并发管理与实时心跳监控 (IPv4, MAC, 序列号, 硬件型号, 电量状态)
5. 局域网快速并发扫描自动发现受控端
6. 批量远程响铃、闪光灯控制与群发弹窗通知
"""

import os
import sys
import json
import time
import socket
import threading
import subprocess
from concurrent.futures import ThreadPoolExecutor, as_completed
import urllib.request
import urllib.error
import re
import csv
import tkinter as tk
from tkinter import ttk, messagebox, simpledialog, filedialog

# Ensure persistent files are ALWAYS resolved relative to script directory
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__)) if "__file__" in locals() and __file__ else os.getcwd()
CONFIG_FILE = os.path.join(SCRIPT_DIR, "remote_devices.json")
HISTORY_FILE = os.path.join(SCRIPT_DIR, "controller_history.json")
LOG_FILE = os.path.join(SCRIPT_DIR, "controller_activity.log")

class Win11MultiDeviceControllerApp(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("Windows 11 安卓设备批量远程控制终端 (含历史与日志审计)")
        self._last_normal_geometry = "1060x780"
        self._load_window_geometry_early()
        self.minsize(940, 700)
        self.configure(bg="#0B132B")

        # In-memory device storage: key = "ip:port", value = dict
        self.devices = {}
        # In-memory history list: list of dict
        self.history = []
        self.heartbeat_running = True
        self.auto_discovery_running = False
        self.executor = ThreadPoolExecutor(max_workers=16)

        self.setup_styles()
        self.create_widgets()

        # Window geometry persistence hooks
        self.bind("<Configure>", self._on_window_configure)
        self.protocol("WM_DELETE_WINDOW", self.on_window_close)
        
        # Load persisted devices & history
        self.load_config()
        self.load_history()

        # Multi-device background heartbeat thread
        self.heartbeat_thread = threading.Thread(target=self.heartbeat_loop, daemon=True)
        self.heartbeat_thread.start()

        # Multi-device UDP listener thread for real-time IP migration notifications
        self.udp_listener_thread = threading.Thread(target=self.udp_listener_loop, daemon=True)
        self.udp_listener_thread.start()

    def _load_window_geometry_early(self):
        default_geo = "1060x780"
        self.geometry(default_geo)
        if os.path.exists(CONFIG_FILE):
            try:
                with open(CONFIG_FILE, "r", encoding="utf-8") as f:
                    cfg = json.load(f)
                win = cfg.get("window", {})
                geo = win.get("geometry")
                if geo and isinstance(geo, str) and "x" in geo:
                    self.geometry(geo)
                    self._last_normal_geometry = geo
                if win.get("is_maximized"):
                    self.after(50, lambda: self.state("zoomed"))
            except Exception:
                pass

    def _on_window_configure(self, event):
        if event.widget == self:
            try:
                if self.state() == "normal":
                    self._last_normal_geometry = self.geometry()
            except Exception:
                pass

    def on_window_close(self):
        try:
            self.save_config()
        except Exception:
            pass
        self.heartbeat_running = False
        self.destroy()

    def setup_styles(self):
        self.style = ttk.Style(self)
        self.style.theme_use("clam")

        self.colors = {
            "bg": "#0B132B",
            "card": "#141E38",
            "card_sub": "#1B284C",
            "primary": "#00E5FF",
            "primary_hover": "#33EBFF",
            "accent": "#7C4DFF",
            "text": "#F0F4FC",
            "subtext": "#8FA0C0",
            "success": "#00E676",
            "warning": "#FFD600",
            "danger": "#FF5252",
            "border": "#263866"
        }

        # Notebook tabs styling
        self.style.configure(
            "TNotebook",
            background=self.colors["bg"],
            borderwidth=0
        )
        self.style.configure(
            "TNotebook.Tab",
            background="#1B284C",
            foreground="#8FA0C0",
            padding=[16, 8],
            font=("Segoe UI", 10, "bold")
        )
        self.style.map(
            "TNotebook.Tab",
            background=[("selected", "#00E5FF")],
            foreground=[("selected", "#000000")]
        )

        # Treeview styling
        self.style.configure(
            "Treeview",
            background="#0E172F",
            foreground="#F0F4FC",
            fieldbackground="#0E172F",
            rowheight=28,
            font=("Segoe UI", 9)
        )
        self.style.configure(
            "Treeview.Heading",
            background="#1B284C",
            foreground="#00E5FF",
            font=("Segoe UI", 9, "bold"),
            relief="flat"
        )
        self.style.map(
            "Treeview",
            background=[("selected", "#253C78")],
            foreground=[("selected", "#00E5FF")]
        )

    def create_widgets(self):
        # Top Header Bar
        top_bar = tk.Frame(self, bg=self.colors["bg"], padx=20, pady=10)
        top_bar.pack(fill="x")

        title_box = tk.Frame(top_bar, bg=self.colors["bg"])
        title_box.pack(side="left")

        tk.Label(
            title_box,
            text="⚡ Windows 11 多安卓设备远程控制与批量管理终端",
            bg=self.colors["bg"],
            fg=self.colors["text"],
            font=("Segoe UI", 15, "bold")
        ).pack(anchor="w")

        tk.Label(
            title_box,
            text="自动持久化记忆已知设备（下次启动免重复添加） · 支持历史指令审计与一键重放",
            bg=self.colors["bg"],
            fg=self.colors["subtext"],
            font=("Segoe UI", 9)
        ).pack(anchor="w")

        self.lbl_stats_badge = tk.Label(
            top_bar,
            text="总设备: 0 | 在线: 0 | 已勾选: 0 | 历史: 0",
            bg="#1B284C",
            fg=self.colors["primary"],
            font=("Segoe UI", 10, "bold"),
            padx=14,
            pady=6,
            relief="flat"
        )
        self.lbl_stats_badge.pack(side="right")

        # Notebook tabs
        self.notebook = ttk.Notebook(self)
        self.notebook.pack(fill="both", expand=True, padx=16, pady=4)

        # Tab 1: Device Management & Batch Controls
        self.tab_devices = tk.Frame(self.notebook, bg=self.colors["bg"])
        self.notebook.add(self.tab_devices, text=" 🖥️ 设备管理与批量控制 ")

        # Tab 2: Execution History & Logs
        self.tab_history = tk.Frame(self.notebook, bg=self.colors["bg"])
        self.notebook.add(self.tab_history, text=" 📜 操作历史与审计日志 ")

        self.build_tab_devices()
        self.build_tab_history()

    # ==================== Tab 1: Devices & Batch Controls ====================

    def build_tab_devices(self):
        main_frame = tk.Frame(self.tab_devices, bg=self.colors["bg"], padx=6, pady=6)
        main_frame.pack(fill="both", expand=True)

        # 1. Add Device & Scan
        add_card = tk.LabelFrame(
            main_frame,
            text=" 1. 添加受控设备与局域网扫描 (自动保存记忆，无需重复添加) ",
            bg=self.colors["card"],
            fg=self.colors["primary"],
            font=("Segoe UI", 10, "bold"),
            padx=12,
            pady=8
        )
        add_card.pack(fill="x", pady=3)

        add_grid = tk.Frame(add_card, bg=self.colors["card"])
        add_grid.pack(fill="x")

        tk.Label(add_grid, text="设备IP:", bg=self.colors["card"], fg=self.colors["subtext"], font=("Segoe UI", 9)).pack(side="left", padx=3)
        self.ent_new_ip = tk.Entry(add_grid, bg=self.colors["card_sub"], fg="#FFF", insertbackground="#FFF", font=("Segoe UI", 9), width=15)
        self.ent_new_ip.insert(0, self.get_local_ip_hint())
        self.ent_new_ip.pack(side="left", padx=3)

        tk.Label(add_grid, text="端口:", bg=self.colors["card"], fg=self.colors["subtext"], font=("Segoe UI", 9)).pack(side="left", padx=3)
        self.ent_new_port = tk.Entry(add_grid, bg=self.colors["card_sub"], fg="#FFF", insertbackground="#FFF", font=("Segoe UI", 9), width=6)
        self.ent_new_port.insert(0, "8888")
        self.ent_new_port.pack(side="left", padx=3)

        tk.Label(add_grid, text="PIN码:", bg=self.colors["card"], fg=self.colors["subtext"], font=("Segoe UI", 9)).pack(side="left", padx=3)
        self.ent_new_pin = tk.Entry(add_grid, bg=self.colors["card_sub"], fg="#FFF", insertbackground="#FFF", font=("Segoe UI", 9, "bold"), width=9)
        self.ent_new_pin.pack(side="left", padx=3)

        tk.Label(add_grid, text="设备备注:", bg=self.colors["card"], fg=self.colors["subtext"], font=("Segoe UI", 9)).pack(side="left", padx=3)
        self.ent_new_remark = tk.Entry(add_grid, bg=self.colors["card_sub"], fg="#FFF", insertbackground="#FFF", font=("Segoe UI", 9), width=14)
        self.ent_new_remark.insert(0, "测试手机A")
        self.ent_new_remark.pack(side="left", padx=3)

        btn_add = tk.Button(
            add_grid,
            text="➕ 绑定并添加设备",
            bg=self.colors["primary"],
            fg="#000",
            font=("Segoe UI", 9, "bold"),
            relief="flat",
            padx=10,
            pady=3,
            cursor="hand2",
            command=self.on_add_device_click
        )
        btn_add.pack(side="left", padx=6)

        btn_scan = tk.Button(
            add_grid,
            text="🔍 局域网快速扫描",
            bg=self.colors["accent"],
            fg="#FFF",
            font=("Segoe UI", 9, "bold"),
            relief="flat",
            padx=10,
            pady=3,
            cursor="hand2",
            command=self.on_scan_lan_click
        )
        btn_scan.pack(side="left", padx=3)

        # 2. Devices Table
        dev_list_card = tk.LabelFrame(
            main_frame,
            text=" 2. 已记忆设备列表 (单击☑快速勾选/取消 · 数据永久保留) ",
            bg=self.colors["card"],
            fg=self.colors["primary"],
            font=("Segoe UI", 10, "bold"),
            padx=10,
            pady=6
        )
        dev_list_card.pack(fill="both", expand=True, pady=3)

        # Toolbar
        sel_bar = tk.Frame(dev_list_card, bg=self.colors["card"])
        sel_bar.pack(fill="x", pady=2)

        tk.Button(sel_bar, text="☑ 全选", bg="#263866", fg="#FFF", font=("Segoe UI", 8), relief="flat", padx=8, pady=2, cursor="hand2", command=self.select_all).pack(side="left", padx=2)
        tk.Button(sel_bar, text="☐ 取消全选", bg="#263866", fg="#FFF", font=("Segoe UI", 8), relief="flat", padx=8, pady=2, cursor="hand2", command=self.deselect_all).pack(side="left", padx=2)
        tk.Button(sel_bar, text="🟢 仅选在线", bg="#263866", fg="#FFF", font=("Segoe UI", 8), relief="flat", padx=8, pady=2, cursor="hand2", command=self.select_online_only).pack(side="left", padx=2)
        tk.Button(sel_bar, text="🔄 立即刷新状态", bg="#263866", fg="#FFF", font=("Segoe UI", 8), relief="flat", padx=8, pady=2, cursor="hand2", command=self.trigger_refresh_all).pack(side="left", padx=2)
        tk.Button(sel_bar, text="✏️ 修改备注", bg="#263866", fg="#FFF", font=("Segoe UI", 8), relief="flat", padx=8, pady=2, cursor="hand2", command=self.edit_selected_remark).pack(side="left", padx=2)
        tk.Button(sel_bar, text="🔑 重新绑定PIN", bg="#263866", fg="#FFF", font=("Segoe UI", 8), relief="flat", padx=8, pady=2, cursor="hand2", command=self.rebind_selected).pack(side="left", padx=2)
        tk.Button(sel_bar, text="🏷️ 资产校准", bg="#1E3A5F", fg="#38BDF8", font=("Segoe UI", 8, "bold"), relief="flat", padx=8, pady=2, cursor="hand2", command=self.open_asset_calibration_dialog).pack(side="left", padx=2)
        tk.Button(sel_bar, text="📊 导出资产(CSV)", bg="#065F46", fg="#A7F3D0", font=("Segoe UI", 8, "bold"), relief="flat", padx=8, pady=2, cursor="hand2", command=self.export_assets_csv).pack(side="left", padx=2)
        tk.Button(sel_bar, text="ℹ️ 硬件详情", bg="#263866", fg="#FFF", font=("Segoe UI", 8), relief="flat", padx=8, pady=2, cursor="hand2", command=self.show_device_details).pack(side="left", padx=2)
        tk.Button(sel_bar, text="🗑️ 移除设备", bg=self.colors["danger"], fg="#FFF", font=("Segoe UI", 8, "bold"), relief="flat", padx=8, pady=2, cursor="hand2", command=self.remove_selected).pack(side="right", padx=2)

        tree_frame = tk.Frame(dev_list_card, bg=self.colors["card"])
        tree_frame.pack(fill="both", expand=True, pady=3)

        columns = ("sel", "status", "remark", "address", "model", "serial", "mac", "battery", "asset", "token")
        self.tree = ttk.Treeview(tree_frame, columns=columns, show="headings", selectmode="extended", height=5)
        
        self.tree.heading("sel", text="☑ 勾选")
        self.tree.heading("status", text="状态")
        self.tree.heading("remark", text="备注名称")
        self.tree.heading("address", text="IP:端口")
        self.tree.heading("model", text="设备型号")
        self.tree.heading("serial", text="序列号 (SN)")
        self.tree.heading("mac", text="物理MAC地址")
        self.tree.heading("battery", text="电量")
        self.tree.heading("asset", text="资产编号")
        self.tree.heading("token", text="授权状态")

        self.tree.column("sel", width=60, anchor="center")
        self.tree.column("status", width=80, anchor="center")
        self.tree.column("remark", width=100, anchor="w")
        self.tree.column("address", width=125, anchor="w")
        self.tree.column("model", width=115, anchor="w")
        self.tree.column("serial", width=145, anchor="w")
        self.tree.column("mac", width=135, anchor="w")
        self.tree.column("battery", width=75, anchor="center")
        self.tree.column("asset", width=95, anchor="center")
        self.tree.column("token", width=75, anchor="center")

        self.tree.tag_configure("online", foreground="#E2E8F0")
        self.tree.tag_configure("offline", foreground="#8A9BB8")

        tree_scroll = ttk.Scrollbar(tree_frame, orient="vertical", command=self.tree.yview)
        self.tree.configure(yscrollcommand=tree_scroll.set)
        self.tree.pack(side="left", fill="both", expand=True)
        tree_scroll.pack(side="right", fill="y")

        self.tree.bind("<Button-1>", self.on_tree_single_click)
        self.tree.bind("<Double-1>", self.on_tree_double_click)
        self.tree.bind("<space>", lambda e: self.toggle_selected_row_checked())

        # Right click context menu
        self.tree_menu = tk.Menu(self.tree, tearoff=0, bg="#1C2541", fg="#FFF", activebackground="#48CAE4", activeforeground="#000")
        self.tree_menu.add_command(label="🏷️ 校准硬件资产 (SN/MAC/资产编号)", command=self.open_asset_calibration_dialog)
        self.tree_menu.add_command(label="ℹ️ 查看详细硬件参数", command=self.show_device_details)
        self.tree_menu.add_command(label="✏️ 修改备注名称", command=self.edit_selected_remark)
        self.tree_menu.add_command(label="🔑 重新绑定PIN码", command=self.rebind_selected)
        self.tree_menu.add_separator()
        self.tree_menu.add_command(label="📊 导出设备资产清单 (CSV)", command=self.export_assets_csv)
        self.tree_menu.add_separator()
        self.tree_menu.add_command(label="🗑️ 解绑并移除设备", command=self.remove_selected)
        self.tree.bind("<Button-3>", self.show_tree_context_menu)

        # 3. Batch Controls
        action_card = tk.LabelFrame(
            main_frame,
            text=" 3. 批量远程命令控制 (将同时并发下发给所有已勾选设备) ",
            bg=self.colors["card"],
            fg=self.colors["primary"],
            font=("Segoe UI", 10, "bold"),
            padx=12,
            pady=6
        )
        action_card.pack(fill="x", pady=3)

        row1 = tk.Frame(action_card, bg=self.colors["card"])
        row1.pack(fill="x", pady=2)

        tk.Label(row1, text="🔔 批量响铃:", bg=self.colors["card"], fg=self.colors["subtext"], font=("Segoe UI", 9, "bold")).pack(side="left", padx=4)
        tk.Button(row1, text="响铃 3秒", bg=self.colors["primary"], fg="#000", font=("Segoe UI", 9, "bold"), relief="flat", padx=8, pady=4, cursor="hand2", command=lambda: self.batch_send_cmd("ring", {"duration": 3}, "🔔 批量响铃 3秒")).pack(side="left", padx=2)
        tk.Button(row1, text="响铃 10秒", bg=self.colors["primary"], fg="#000", font=("Segoe UI", 9, "bold"), relief="flat", padx=8, pady=4, cursor="hand2", command=lambda: self.batch_send_cmd("ring", {"duration": 10}, "🔔 批量响铃 10秒")).pack(side="left", padx=2)
        tk.Button(row1, text="响铃 30秒", bg=self.colors["primary"], fg="#000", font=("Segoe UI", 9, "bold"), relief="flat", padx=8, pady=4, cursor="hand2", command=lambda: self.batch_send_cmd("ring", {"duration": 30}, "🔔 批量响铃 30秒")).pack(side="left", padx=2)
        tk.Button(row1, text="响铃 60秒", bg=self.colors["primary"], fg="#000", font=("Segoe UI", 9, "bold"), relief="flat", padx=8, pady=4, cursor="hand2", command=lambda: self.batch_send_cmd("ring", {"duration": 60}, "🔔 批量响铃 60秒")).pack(side="left", padx=2)

        tk.Label(row1, text="自定义:", bg=self.colors["card"], fg=self.colors["subtext"], font=("Segoe UI", 9)).pack(side="left", padx=(6, 2))
        self.ent_ring_duration = tk.Entry(row1, bg=self.colors["card_sub"], fg="#FFF", insertbackground="#FFF", width=4, justify="center", font=("Segoe UI", 9, "bold"))
        self.ent_ring_duration.insert(0, "3")
        self.ent_ring_duration.pack(side="left", padx=2)
        tk.Label(row1, text="秒", bg=self.colors["card"], fg=self.colors["subtext"], font=("Segoe UI", 9)).pack(side="left")
        tk.Button(row1, text="🔔 响铃", bg="#0EA5E9", fg="#FFF", font=("Segoe UI", 9, "bold"), relief="flat", padx=8, pady=4, cursor="hand2", command=self.on_custom_ring_click).pack(side="left", padx=3)

        tk.Button(row1, text="⏹️ 停止响铃", bg=self.colors["danger"], fg="#FFF", font=("Segoe UI", 9, "bold"), relief="flat", padx=10, pady=4, cursor="hand2", command=lambda: self.batch_send_cmd("ring_stop", {}, "⏹️ 停止响铃")).pack(side="left", padx=3)

        tk.Label(row1, text=" | 🔦 批量闪光灯:", bg=self.colors["card"], fg=self.colors["subtext"], font=("Segoe UI", 9, "bold")).pack(side="left", padx=8)
        tk.Button(row1, text="常亮开启", bg=self.colors["accent"], fg="#FFF", font=("Segoe UI", 9, "bold"), relief="flat", padx=10, pady=4, cursor="hand2", command=lambda: self.batch_send_cmd("flash", {"mode": "on"}, "🔦 闪光灯常亮开启")).pack(side="left", padx=3)
        tk.Button(row1, text="⚡ 爆闪 (10s)", bg=self.colors["warning"], fg="#000", font=("Segoe UI", 9, "bold"), relief="flat", padx=10, pady=4, cursor="hand2", command=lambda: self.batch_send_cmd("flash", {"mode": "strobe", "duration": 10}, "⚡ 闪光灯警示爆闪 10秒")).pack(side="left", padx=3)
        tk.Button(row1, text="关闭闪光灯", bg="#374151", fg="#FFF", font=("Segoe UI", 9), relief="flat", padx=10, pady=4, cursor="hand2", command=lambda: self.batch_send_cmd("flash", {"mode": "off"}, "🚫 关闭闪光灯")).pack(side="left", padx=3)

        # Row 1.5: Volume control row
        row_vol = tk.Frame(action_card, bg=self.colors["card"])
        row_vol.pack(fill="x", pady=2)
        tk.Label(row_vol, text="🔊 批量音量:", bg=self.colors["card"], fg=self.colors["subtext"], font=("Segoe UI", 9, "bold")).pack(side="left", padx=4)
        tk.Button(row_vol, text="音量 10%", bg="#1E293B", fg="#38BDF8", font=("Segoe UI", 8, "bold"), relief="flat", padx=8, pady=2, cursor="hand2", command=lambda: self.batch_send_cmd("volume", {"percent": 10}, "音量调整到10%")).pack(side="left", padx=2)
        tk.Button(row_vol, text="音量 30%", bg="#1E293B", fg="#38BDF8", font=("Segoe UI", 8, "bold"), relief="flat", padx=8, pady=2, cursor="hand2", command=lambda: self.batch_send_cmd("volume", {"percent": 30}, "音量调整到30%")).pack(side="left", padx=2)
        tk.Button(row_vol, text="音量 50%", bg="#1E293B", fg="#38BDF8", font=("Segoe UI", 8, "bold"), relief="flat", padx=8, pady=2, cursor="hand2", command=lambda: self.batch_send_cmd("volume", {"percent": 50}, "音量调整到50%")).pack(side="left", padx=2)
        tk.Button(row_vol, text="音量 100%", bg="#1E293B", fg="#38BDF8", font=("Segoe UI", 8, "bold"), relief="flat", padx=8, pady=2, cursor="hand2", command=lambda: self.batch_send_cmd("volume", {"percent": 100}, "音量调整到100%")).pack(side="left", padx=2)
        tk.Label(row_vol, text="(💡 响铃结束后系统会自动静音/恢复10%并还原音量)", bg=self.colors["card"], fg="#64748B", font=("Segoe UI", 8)).pack(side="left", padx=8)

        notify_frame = tk.Frame(action_card, bg=self.colors["card"], pady=4)
        notify_frame.pack(fill="x")

        tk.Label(notify_frame, text="💬 批量弹窗标题:", bg=self.colors["card"], fg=self.colors["subtext"], font=("Segoe UI", 9)).grid(row=0, column=0, sticky="w")
        self.ent_batch_title = tk.Entry(notify_frame, bg=self.colors["card_sub"], fg="#FFF", insertbackground="#FFF", width=18, font=("Segoe UI", 9))
        self.ent_batch_title.insert(0, "Windows 11 批量提醒")
        self.ent_batch_title.grid(row=0, column=1, padx=4)

        tk.Label(notify_frame, text="弹窗正文:", bg=self.colors["card"], fg=self.colors["subtext"], font=("Segoe UI", 9)).grid(row=0, column=2, sticky="w", padx=4)
        self.ent_batch_msg = tk.Entry(notify_frame, bg=self.colors["card_sub"], fg="#FFF", insertbackground="#FFF", width=42, font=("Segoe UI", 9))
        self.ent_batch_msg.insert(0, "请注意：电脑端已向所有设备发送同步指令！")
        self.ent_batch_msg.grid(row=0, column=3, padx=4)

        btn_batch_notify = tk.Button(
            notify_frame,
            text="🚀 群发弹窗通知",
            bg=self.colors["primary"],
            fg="#000",
            font=("Segoe UI", 9, "bold"),
            relief="flat",
            padx=12,
            pady=3,
            cursor="hand2",
            command=self.on_batch_notify
        )
        btn_batch_notify.grid(row=0, column=4, padx=6)

        # 4. Logs Console
        log_card = tk.LabelFrame(
            main_frame,
            text=" 4. 实时操作日志 (自动追加记录至本地 controller_activity.log) ",
            bg=self.colors["card"],
            fg=self.colors["primary"],
            font=("Segoe UI", 10, "bold"),
            padx=10,
            pady=4
        )
        log_card.pack(fill="both", expand=True, pady=3)

        log_top_tools = tk.Frame(log_card, bg=self.colors["card"])
        log_top_tools.pack(fill="x", pady=1)

        tk.Button(log_top_tools, text="🧹 清空屏幕日志", bg="#263866", fg="#FFF", font=("Segoe UI", 8), relief="flat", padx=6, pady=1, cursor="hand2", command=self.clear_console_log).pack(side="left", padx=2)
        tk.Button(log_top_tools, text="📂 打开完整日志文件", bg="#263866", fg="#FFF", font=("Segoe UI", 8), relief="flat", padx=6, pady=1, cursor="hand2", command=self.open_log_file).pack(side="left", padx=4)

        self.txt_log = tk.Text(
            log_card,
            bg="#070C1A",
            fg="#A5B4D4",
            insertbackground="#FFF",
            font=("Consolas", 9),
            relief="flat",
            height=5
        )
        log_scroll = ttk.Scrollbar(log_card, orient="vertical", command=self.txt_log.yview)
        self.txt_log.configure(yscrollcommand=log_scroll.set)
        self.txt_log.pack(side="left", fill="both", expand=True)
        log_scroll.pack(side="right", fill="y")

    # ==================== Tab 2: Execution History & Logs ====================

    def build_tab_history(self):
        h_frame = tk.Frame(self.tab_history, bg=self.colors["bg"], padx=10, pady=8)
        h_frame.pack(fill="both", expand=True)

        # Toolbar
        h_bar = tk.Frame(h_frame, bg=self.colors["bg"])
        h_bar.pack(fill="x", pady=4)

        tk.Button(
            h_bar,
            text="🔁 再次执行选中历史命令",
            bg=self.colors["primary"],
            fg="#000",
            font=("Segoe UI", 9, "bold"),
            relief="flat",
            padx=12,
            pady=4,
            cursor="hand2",
            command=self.rerun_selected_history
        ) .pack(side="left", padx=4)

        tk.Button(
            h_bar,
            text="📋 载入到控制面板",
            bg=self.colors["accent"],
            fg="#FFF",
            font=("Segoe UI", 9),
            relief="flat",
            padx=10,
            pady=4,
            cursor="hand2",
            command=self.load_history_to_inputs
        ).pack(side="left", padx=4)

        tk.Button(
            h_bar,
            text="💾 导出历史记录 (JSON)",
            bg="#263866",
            fg="#FFF",
            font=("Segoe UI", 9),
            relief="flat",
            padx=10,
            pady=4,
            cursor="hand2",
            command=self.export_history_json
        ).pack(side="left", padx=4)

        tk.Button(
            h_bar,
            text="📂 查看活动日志文件",
            bg="#263866",
            fg="#FFF",
            font=("Segoe UI", 9),
            relief="flat",
            padx=10,
            pady=4,
            cursor="hand2",
            command=self.open_log_file
        ).pack(side="left", padx=4)

        tk.Button(
            h_bar,
            text="🗑️ 清空所有历史记录",
            bg=self.colors["danger"],
            fg="#FFF",
            font=("Segoe UI", 9, "bold"),
            relief="flat",
            padx=10,
            pady=4,
            cursor="hand2",
            command=self.clear_all_history
        ).pack(side="right", padx=4)

        # History Treeview
        h_tree_card = tk.LabelFrame(
            h_frame,
            text=" 📜 历史指令审计列表 (按时间倒序排列 · 双击或选中行查看详细响应数据) ",
            bg=self.colors["card"],
            fg=self.colors["primary"],
            font=("Segoe UI", 10, "bold"),
            padx=8,
            pady=6
        )
        h_tree_card.pack(fill="both", expand=True, pady=4)

        h_cols = ("time", "action", "targets_count", "targets", "result")
        self.history_tree = ttk.Treeview(h_tree_card, columns=h_cols, show="headings", selectmode="browse", height=9)

        self.history_tree.heading("time", text="执行时间")
        self.history_tree.heading("action", text="下发命令")
        self.history_tree.heading("targets_count", text="目标数")
        self.history_tree.heading("targets", text="目标设备列表")
        self.history_tree.heading("result", text="下发结果与摘要")

        self.history_tree.column("time", width=145, anchor="center")
        self.history_tree.column("action", width=180, anchor="w")
        self.history_tree.column("targets_count", width=65, anchor="center")
        self.history_tree.column("targets", width=250, anchor="w")
        self.history_tree.column("result", width=250, anchor="w")

        h_scroll = ttk.Scrollbar(h_tree_card, orient="vertical", command=self.history_tree.yview)
        self.history_tree.configure(yscrollcommand=h_scroll.set)
        self.history_tree.pack(side="left", fill="both", expand=True)
        h_scroll.pack(side="right", fill="y")

        self.history_tree.bind("<<TreeviewSelect>>", self.on_history_select)

        # Detail preview card
        detail_card = tk.LabelFrame(
            h_frame,
            text=" 🔍 选中历史记录的详细参数与各设备回传数据 ",
            bg=self.colors["card"],
            fg=self.colors["primary"],
            font=("Segoe UI", 10, "bold"),
            padx=8,
            pady=6
        )
        detail_card.pack(fill="both", expand=True, pady=4)

        self.txt_history_detail = tk.Text(
            detail_card,
            bg="#070C1A",
            fg="#A5B4D4",
            insertbackground="#FFF",
            font=("Consolas", 9),
            relief="flat",
            height=6
        )
        detail_scroll = ttk.Scrollbar(detail_card, orient="vertical", command=self.txt_history_detail.yview)
        self.txt_history_detail.configure(yscrollcommand=detail_scroll.set)
        self.txt_history_detail.pack(side="left", fill="both", expand=True)
        detail_scroll.pack(side="right", fill="y")

    # ==================== Logging & Storage ====================

    def log(self, text):
        now_time = time.strftime("%H:%M:%S")
        line = f"[{now_time}] {text}\n"
        self.txt_log.insert("end", line)
        self.txt_log.see("end")

        # Persistent append to file
        try:
            full_time = time.strftime("%Y-%m-%d %H:%M:%S")
            with open(LOG_FILE, "a", encoding="utf-8") as f:
                f.write(f"[{full_time}] {text}\n")
        except:
            pass

    def clear_console_log(self):
        self.txt_log.delete("1.0", "end")

    def open_log_file(self):
        if not os.path.exists(LOG_FILE):
            try:
                with open(LOG_FILE, "w", encoding="utf-8") as f:
                    f.write(f"# Windows 11 控制端日志启动于 {time.ctime()}\n")
            except:
                pass
        try:
            if sys.platform == "win32":
                os.startfile(LOG_FILE)
            else:
                subprocess.Popen(["xdg-open", LOG_FILE])
        except Exception as e:
            messagebox.showinfo("日志文件路径", f"日志文件保存于:\n{LOG_FILE}")

    def get_local_ip_hint(self):
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
            s.close()
            parts = ip.split(".")
            if len(parts) == 4:
                return f"{parts[0]}.{parts[1]}.{parts[2]}.100"
        except:
            pass
        return "192.168.1.100"

    # ==================== Device Management & Table View ====================

    def update_treeview(self):
        existing_items = set(self.tree.get_children())
        online_count = 0
        selected_count = 0

        for key, dev in self.devices.items():
            sel_text = "☑" if dev.get("selected", True) else "☐"
            if dev.get("selected", True):
                selected_count += 1

            online = dev.get("online", False)
            if online:
                online_count += 1
                status_text = "🟢 在线"
                tag = "online"
            else:
                status_text = "⚪ 离线"
                tag = "offline"

            remark = dev.get("remark", "未命名")
            address = f"{dev.get('ip')}:{dev.get('port', 8888)}"
            info = dev.get("deviceInfo", {})
            model = info.get("model", "--")
            serial = info.get("serial", "--")
            mac = info.get("mac", "--")

            bat_level = info.get("batteryLevel", -1)
            is_charging = info.get("isCharging", False)
            if bat_level >= 0:
                bat_str = f"⚡{bat_level}%" if is_charging else f"{bat_level}%"
            else:
                bat_str = "--"

            token_str = "已授权" if dev.get("token") else "未授权"
            asset_str = info.get("assetTag") or "--"

            vals = (sel_text, status_text, remark, address, model, serial, mac, bat_str, asset_str, token_str)

            if key in existing_items:
                self.tree.item(key, values=vals, tags=(tag,))
                existing_items.remove(key)
            else:
                self.tree.insert("", "end", iid=key, values=vals, tags=(tag,))

        for old_key in existing_items:
            self.tree.delete(old_key)

        total_count = len(self.devices)
        history_count = len(self.history)
        self.lbl_stats_badge.config(
            text=f"总设备: {total_count} | 🟢 在线: {online_count} | 🎯 已勾选: {selected_count} | 📜 历史: {history_count}"
        )

    def on_custom_ring_click(self):
        val = self.ent_ring_duration.get().strip()
        try:
            sec = int(val)
            if sec <= 0:
                sec = 15
        except:
            sec = 15
        self.batch_send_cmd("ring", {"duration": sec}, f"🔔 批量响铃 {sec}秒")

    def show_tree_context_menu(self, event):
        item = self.tree.identify_row(event.y)
        if item:
            if item not in self.tree.selection():
                self.tree.selection_set(item)
            self.tree_menu.post(event.x_root, event.y_root)

    def on_tree_single_click(self, event):
        region = self.tree.identify("region", event.x, event.y)
        if region == "heading":
            col = self.tree.identify_column(event.x)
            if col == "#1":
                # Single-click heading to toggle all selections
                any_unselected = any(not v.get("selected", True) for v in self.devices.values())
                for dev in self.devices.values():
                    dev["selected"] = any_unselected
                self.save_config()
                self.update_treeview()
            return

        item_id = self.tree.identify_row(event.y)
        if not item_id or item_id not in self.devices:
            return

        col = self.tree.identify_column(event.x)
        # Click on column #1 (☑ 勾选): instant single-click toggle!
        if col == "#1":
            self.devices[item_id]["selected"] = not self.devices[item_id].get("selected", True)
            self.save_config()
            self.update_treeview()
            self.tree.selection_set(item_id)
            return "break"

    def on_tree_double_click(self, event):
        item_id = self.tree.identify_row(event.y)
        if item_id and item_id in self.devices:
            self.devices[item_id]["selected"] = not self.devices[item_id].get("selected", True)
            self.save_config()
            self.update_treeview()

    def toggle_selected_row_checked(self):
        sel_items = self.tree.selection()
        if sel_items:
            for item_id in sel_items:
                if item_id in self.devices:
                    self.devices[item_id]["selected"] = not self.devices[item_id].get("selected", True)
            self.save_config()
            self.update_treeview()

    def select_all(self):
        for dev in self.devices.values():
            dev["selected"] = True
        self.save_config()
        self.update_treeview()

    def deselect_all(self):
        for dev in self.devices.values():
            dev["selected"] = False
        self.save_config()
        self.update_treeview()

    def select_online_only(self):
        for dev in self.devices.values():
            dev["selected"] = dev.get("online", False)
        self.save_config()
        self.update_treeview()

    def on_add_device_click(self):
        ip = self.ent_new_ip.get().strip()
        port_str = self.ent_new_port.get().strip()
        pin = self.ent_new_pin.get().strip()
        remark = self.ent_new_remark.get().strip() or f"设备_{ip.split('.')[-1]}"

        if not ip:
            messagebox.showwarning("提示", "请输入安卓受控端 IP 地址！")
            return

        port = int(port_str) if port_str.isdigit() else 8888
        key = f"{ip}:{port}"

        # 1. Check if this exact IP:Port was already bound with a valid token
        if key in self.devices and self.devices[key].get("token"):
            dev = self.devices[key]
            self.log(f"🔍 检测到设备 [{dev.get('remark')}] ({key}) 此前已授权绑定过，正在激活长效连接...")
            threading.Thread(target=self._async_verify_existing_device, args=(key, ip, port, dev), daemon=True).start()
            return

        # 2. Check if this device (or another known device whose IP changed) is already authorized
        self.log(f"正在探测并验证设备 {key}，检查是否已绑定或需首次配对...")
        threading.Thread(target=self._async_smart_add_or_bind, args=(key, ip, port, pin, remark), daemon=True).start()

    def _async_verify_existing_device(self, key, ip, port, dev):
        token = dev.get("token", "")
        url = f"http://{ip}:{port}/api/verify?token={token}"
        try:
            req = urllib.request.Request(url)
            req.add_header("X-Auth-Token", token)
            with urllib.request.urlopen(req, timeout=4.0) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                if data.get("valid") or data.get("code") == 0:
                    dev["online"] = True
                    dev["last_seen"] = time.time()
                    dev["fail_count"] = 0
                    self.after(0, self.save_config)
                    self.after(0, self.update_treeview)
                    self.after(0, lambda: self.log(f"✅ 设备 [{dev.get('remark')}] ({key}) 已直接复用永久信任密钥，无需重复输入PIN码或再次授权！"))
                    self.after(0, lambda: messagebox.showinfo(
                        "设备已绑定",
                        f"设备 [{dev.get('remark')}] ({key}) 此前已授权绑定过！\n\n已成功复用原长效密钥并激活连接。\n无需重复输入PIN码，无需再次授权！"
                    ))
                    return
        except:
            pass

        # If token check failed on target, check if user provided PIN to re-pair
        pin = self.ent_new_pin.get().strip()
        if pin:
            self._async_bind_device(key, ip, port, pin, dev.get("remark", f"设备_{ip.split('.')[-1]}"))
        else:
            self.after(0, lambda: messagebox.showwarning(
                "需要重新授权",
                f"设备 [{dev.get('remark')}] 原信任密钥已在安卓端被手动解除。\n请输入安卓端当前屏幕显示的 6 位 PIN 码重新绑定。"
            ))

    def _async_smart_add_or_bind(self, key, ip, port, pin, remark):
        # 1. Probe /api/ping to check availability and read hardware identification
        try:
            url = f"http://{ip}:{port}/api/ping"
            req = urllib.request.Request(url)
            with urllib.request.urlopen(req, timeout=4.0) as resp:
                data = json.loads(resp.read().decode("utf-8"))
        except Exception as e:
            self.after(0, lambda: messagebox.showerror(
                "无法连接",
                f"无法连接到受控设备 {key}！\n\n请确认：\n1. 安卓端已开启远程受控服务\n2. 手机与电脑已连接到同一局域网 Wi-Fi\n3. 手机屏幕显示的 IP 与端口填写无误"
            ))
            return

        serial = data.get("serial")
        mac = data.get("mac")
        android_id = data.get("androidId")
        model = data.get("model", "Android")

        # 2. Check if this device matches any existing bound device in self.devices
        matched_key = None
        matched_dev = None
        for k, dev in list(self.devices.items()):
            if not dev.get("token"):
                continue
            dev_serial = dev.get("deviceInfo", {}).get("serial")
            dev_mac = dev.get("deviceInfo", {}).get("mac")
            dev_aid = dev.get("deviceInfo", {}).get("androidId")
            if serial and serial != "--" and dev_serial and dev_serial == serial:
                matched_key = k
                matched_dev = dev
                break
            if android_id and dev_aid and dev_aid == android_id:
                matched_key = k
                matched_dev = dev
                break
            if mac and mac != "02:00:00:00:00:00" and not mac.startswith("FE:") and dev_mac and dev_mac == mac:
                matched_key = k
                matched_dev = dev
                break

        if matched_dev:
            # Device hardware matches an already bound device! Test existing token
            token = matched_dev.get("token")
            try:
                v_url = f"http://{ip}:{port}/api/verify?token={token}"
                v_req = urllib.request.Request(v_url)
                v_req.add_header("X-Auth-Token", token)
                with urllib.request.urlopen(v_req, timeout=4.0) as v_resp:
                    v_data = json.loads(v_resp.read().decode("utf-8"))
                    if v_data.get("valid") or v_data.get("code") == 0:
                        # Verified! Smoothly migrate device IP
                        dev = self.devices.pop(matched_key)
                        dev["id"] = key
                        dev["ip"] = ip
                        dev["port"] = port
                        dev["online"] = True
                        dev["last_seen"] = time.time()
                        dev["fail_count"] = 0
                        if "deviceInfo" not in dev:
                            dev["deviceInfo"] = {}
                        dev["deviceInfo"]["model"] = model
                        if serial and serial != "--": dev["deviceInfo"]["serial"] = serial
                        if mac and mac != "02:00:00:00:00:00": dev["deviceInfo"]["mac"] = mac
                        if android_id: dev["deviceInfo"]["androidId"] = android_id

                        self.devices[key] = dev
                        self.after(0, self.save_config)
                        self.after(0, self.update_treeview)
                        self.after(0, lambda: self.log(
                            f"🔄 自动识别已绑定设备 [{dev.get('remark')}] (SN: {serial})！IP地址变更为 {key}，已直接复用原有密钥，免除重新输入PIN码！"
                        ))
                        self.after(0, lambda: messagebox.showinfo(
                            "识别到已绑定设备",
                            f"系统已成功识别该设备为此前已绑定的 [{dev.get('remark')}] (型号: {model}，SN: {serial})！\n\n"
                            f"检测到设备 IP 已变更为: {key}\n"
                            f"已自动平滑更新设备地址并复用长效密钥，无需重新输入 PIN 码或再次授权！"
                        ))
                        return
            except:
                pass

        # 3. Truly new unbound device -> require PIN for the first time
        if not pin:
            self.after(0, lambda: messagebox.showwarning(
                "首次绑定提示",
                f"检测到设备 {key} ({model}) 为首次添加！\n\n请输入安卓端屏幕上显示的 6 位数字 PIN 码完成首次配对。\n(配对成功后将建立永久长效信任，日后IP变更或手机重启均无需重复输入密码)"
            ))
            return

        # 4. First-time bind with PIN
        self._async_bind_device(key, ip, port, pin, remark)

    def _async_bind_device(self, key, ip, port, pin, remark):
        url = f"http://{ip}:{port}/api/bind"
        payload = json.dumps({"pin": pin, "clientName": "Win11 控制台"}).encode("utf-8")
        req = urllib.request.Request(url, data=payload, headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=6) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                if data.get("code") == 0:
                    token = data.get("token", "")
                    client_secret = data.get("clientSecret", "")
                    dev_info = data.get("deviceInfo", {})

                    # If MAC is restricted (02:00:00:00:00:00) or missing, resolve link-layer MAC via Windows ARP!
                    current_mac = dev_info.get("mac", "")
                    if not current_mac or current_mac == "02:00:00:00:00:00" or current_mac.startswith("FE:"):
                        arp_mac = self.resolve_mac_via_arp(ip)
                        if arp_mac:
                            dev_info["mac"] = arp_mac
                            dev_info["macSource"] = "局域网ARP物理层直采 (真实网卡MAC)"

                    self.devices[key] = {
                        "id": key,
                        "ip": ip,
                        "port": port,
                        "pin": pin,
                        "token": token,
                        "clientSecret": client_secret,
                        "remark": remark,
                        "online": True,
                        "selected": True,
                        "deviceInfo": dev_info,
                        "last_seen": time.time(),
                        "fail_count": 0
                    }
                    self.after(0, self.save_config)
                    self.after(0, self.update_treeview)
                    self.after(0, lambda: self.log(f"🎉 设备 [{remark}] ({key}) 永久信任绑定成功！已建立长效密钥，手机清理后台或变更PIN码均无需重新绑定。"))
                    self.after(0, lambda: messagebox.showinfo("绑定成功", f"设备 [{remark}] 永久绑定成功！\n\n已与该设备建立永久数字密钥配对。\n即使安卓手机重启、清理后台或PIN码刷新，均保持永久有效，无须重新绑定！\n(只有在电脑端点击【移除设备】才会解除绑定关系)\n\n设备型号: {dev_info.get('model', 'Android')}\n序列号: {dev_info.get('serial', '--')}"))
                else:
                    msg = data.get("message", "绑定失败")
                    self.after(0, lambda: self.log(f"❌ 绑定被拒绝 [{key}]: {msg}"))
                    self.after(0, lambda: messagebox.showerror("绑定失败", msg))
        except urllib.error.HTTPError as e:
            err_msg = e.read().decode("utf-8", errors="ignore")
            try:
                msg = json.loads(err_msg).get("message", f"HTTP {e.code}")
            except:
                msg = f"HTTP {e.code}: {e.reason}"
            self.after(0, lambda: self.log(f"❌ 绑定失败 [{key}]: {msg}"))
            self.after(0, lambda: messagebox.showerror("绑定失败", f"受控端拒绝连接: {msg}"))
        except Exception as e:
            self.after(0, lambda: self.log(f"❌ 无法连接 [{key}]: {str(e)}"))
            self.after(0, lambda: messagebox.showerror("连接异常", f"无法连接到 {key}，请检查手机与电脑是否处于同一 Wi-Fi 且已开启服务。"))

    def on_scan_lan_click(self):
        base_ip = self.ent_new_ip.get().strip()
        port_str = self.ent_new_port.get().strip()
        port = int(port_str) if port_str.isdigit() else 8888

        parts = base_ip.split(".")
        if len(parts) != 4:
            messagebox.showwarning("提示", "请输入合法的局域网 IP，以便确定网段 (如 192.168.1.100)")
            return

        subnet_prefix = f"{parts[0]}.{parts[1]}.{parts[2]}"
        self.log(f"🔍 启动局域网后台快速扫描 (网段: {subnet_prefix}.1 ~ 254，端口: {port})...")
        threading.Thread(target=self._run_lan_scan, args=(subnet_prefix, port), daemon=True).start()

    def _run_lan_scan(self, subnet_prefix, port):
        found_devices = []
        scan_executor = ThreadPoolExecutor(max_workers=30)
        futures = {}

        for host in range(1, 255):
            target_ip = f"{subnet_prefix}.{host}"
            fut = scan_executor.submit(self._check_receiver_port, target_ip, port)
            futures[fut] = target_ip

        for fut in as_completed(futures):
            target_ip = futures[fut]
            try:
                is_active, data = fut.result()
                if is_active:
                    found_devices.append((target_ip, data or {}))
                    self.after(0, lambda ip=target_ip: self.log(f"✨ 发现活跃安卓受控端: {ip}:{port}"))
            except:
                pass

        scan_executor.shutdown(wait=False)

        def on_scan_done():
            self.log(f"🏁 局域网扫描完成！共发现 {len(found_devices)} 个受控端设备。")
            if found_devices:
                migrated_count = 0
                new_added_count = 0
                for f_ip, data in found_devices:
                    f_key = f"{f_ip}:{port}"
                    serial = data.get("serial")
                    mac = data.get("mac")
                    android_id = data.get("androidId")
                    model = data.get("model", "Android")

                    # Check if this matches an existing bound device
                    matched_key = None
                    matched_dev = None
                    for k, dev in list(self.devices.items()):
                        if not dev.get("token"):
                            continue
                        dev_serial = dev.get("deviceInfo", {}).get("serial")
                        dev_mac = dev.get("deviceInfo", {}).get("mac")
                        dev_aid = dev.get("deviceInfo", {}).get("androidId")
                        if serial and serial != "--" and dev_serial and dev_serial == serial:
                            matched_key = k
                            matched_dev = dev
                            break
                        if android_id and dev_aid and dev_aid == android_id:
                            matched_key = k
                            matched_dev = dev
                            break
                        if mac and mac != "02:00:00:00:00:00" and not mac.startswith("FE:") and dev_mac and dev_mac == mac:
                            matched_key = k
                            matched_dev = dev
                            break

                    if matched_dev:
                        if matched_key != f_key:
                            # Auto-migrate IP
                            dev = self.devices.pop(matched_key)
                            dev["id"] = f_key
                            dev["ip"] = f_ip
                            dev["port"] = port
                            dev["online"] = True
                            dev["last_seen"] = time.time()
                            if "deviceInfo" not in dev:
                                dev["deviceInfo"] = {}
                            dev["deviceInfo"]["model"] = model
                            if serial and serial != "--": dev["deviceInfo"]["serial"] = serial
                            if mac and mac != "02:00:00:00:00:00": dev["deviceInfo"]["mac"] = mac
                            if android_id: dev["deviceInfo"]["androidId"] = android_id
                            self.devices[f_key] = dev
                            migrated_count += 1
                            self.log(f"🔄 扫描识别到已绑定设备 [{dev.get('remark')}] IP迁移为 {f_key}，已自动更新！")
                    elif f_key not in self.devices:
                        dev_info = {}
                        if model: dev_info["model"] = model
                        if serial: dev_info["serial"] = serial
                        if mac: dev_info["mac"] = mac
                        if android_id: dev_info["androidId"] = android_id
                        self.devices[f_key] = {
                            "id": f_key,
                            "ip": f_ip,
                            "port": port,
                            "pin": "",
                            "token": "",
                            "remark": f"扫描设备_{f_ip.split('.')[-1]}",
                            "online": True,
                            "selected": True,
                            "deviceInfo": dev_info,
                            "last_seen": time.time()
                        }
                        new_added_count += 1

                self.save_config()
                self.update_treeview()
                msg = f"在网段 {subnet_prefix}.* 扫描完成！共发现 {len(found_devices)} 台活跃设备。\n\n"
                if migrated_count > 0:
                    msg += f"• 成功自动识别并迁移 {migrated_count} 台已绑定的设备IP (免重新授权)\n"
                if new_added_count > 0:
                    msg += f"• 新增加入 {new_added_count} 台新设备到列表 (首次使用请点击【重新绑定PIN】授权)\n"
                messagebox.showinfo("扫描结果", msg)
            else:
                messagebox.showinfo("扫描完成", f"在网段 {subnet_prefix}.* 未找到开启服务的安卓设备。\n请确保手机已打开控制开关，且位于同一Wi-Fi网络。")

        self.after(0, on_scan_done)

    def _check_receiver_port(self, ip, port):
        try:
            url = f"http://{ip}:{port}/api/ping"
            req = urllib.request.Request(url)
            with urllib.request.urlopen(req, timeout=0.8) as response:
                if response.status == 200:
                    data = json.loads(response.read().decode("utf-8"))
                    if data.get("server") == "AndroidRemoteReceiver" or data.get("code") == 0:
                        return (True, data)
        except:
            pass
        return (False, None)

    def get_selected_device_keys(self):
        return [k for k, v in self.devices.items() if v.get("selected", True)]

    def edit_selected_remark(self):
        sel_items = self.tree.selection()
        if not sel_items:
            messagebox.showwarning("提示", "请在列表中选中一行设备进行修改备注！")
            return
        key = sel_items[0]
        dev = self.devices.get(key)
        if not dev:
            return

        old_remark = dev.get("remark", "")
        new_remark = simpledialog.askstring("修改备注", f"请输入设备 [{key}] 的新备注：", initialvalue=old_remark)
        if new_remark is not None:
            dev["remark"] = new_remark.strip()
            self.save_config()
            self.update_treeview()
            self.log(f"已更新设备 [{key}] 备注为: {dev['remark']}")

    def rebind_selected(self):
        sel_items = self.tree.selection()
        if not sel_items:
            messagebox.showwarning("提示", "请在列表中先选中需要绑定的设备！")
            return
        key = sel_items[0]
        dev = self.devices.get(key)
        if not dev:
            return

        pin = simpledialog.askstring("绑定授权", f"请输入设备 [{dev.get('remark')}] ({key}) 屏幕上显示的 6 位 PIN 码：")
        if pin:
            threading.Thread(
                target=self._async_bind_device,
                args=(key, dev["ip"], dev["port"], pin.strip(), dev.get("remark", "手机")),
                daemon=True
            ).start()

    def resolve_mac_via_arp(self, ip):
        """Query system ARP cache directly to obtain the REAL physical link-layer MAC address."""
        if not ip or ip in ("127.0.0.1", "localhost"):
            return None
        try:
            # On Windows: arp -a <ip>
            res = subprocess.run(["arp", "-a", ip], capture_output=True, text=True, timeout=2.0)
            output = res.stdout or ""
            for line in output.splitlines():
                line_clean = line.strip()
                if ip in line_clean:
                    match = re.search(r'([0-9a-fA-F]{2}[-:][0-9a-fA-F]{2}[-:][0-9a-fA-F]{2}[-:][0-9a-fA-F]{2}[-:][0-9a-fA-F]{2}[-:][0-9a-fA-F]{2})', line_clean)
                    if match:
                        mac_raw = match.group(1).replace("-", ":").upper()
                        if mac_raw not in ("00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF", "02:00:00:00:00:00"):
                            return mac_raw
        except Exception:
            pass
        return None

    def open_asset_calibration_dialog(self):
        sel_items = self.tree.selection()
        if not sel_items:
            messagebox.showwarning("提示", "请在列表中先选中需要校准资产信息的设备！")
            return
        key = sel_items[0]
        dev = self.devices.get(key)
        if not dev:
            return

        info = dev.get("deviceInfo", {})
        ip = dev.get("ip", "")
        port = dev.get("port", 8888)
        token = dev.get("token", "")

        # Try to resolve ARP MAC in background if current is empty or 02:00:00:00:00:00
        arp_mac = self.resolve_mac_via_arp(ip)
        current_mac = info.get("mac", "")
        if (not current_mac or current_mac == "02:00:00:00:00:00" or current_mac.startswith("FE:")) and arp_mac:
            current_mac = arp_mac

        current_serial = info.get("serial", "")
        current_asset = info.get("assetTag", "")
        current_android_id = info.get("androidId", "")
        serial_source = info.get("serialSource", "系统识别")

        # Open calibration modal dialog
        dlg = tk.Toplevel(self)
        dlg.title(f"硬件资产信息校准 - {dev.get('remark')} ({ip})")
        dlg.geometry("520x430")
        dlg.minsize(480, 400)
        dlg.configure(bg="#0F172A")
        dlg.transient(self)
        dlg.grab_set()

        header = tk.Frame(dlg, bg="#0F172A")
        header.pack(fill="x", padx=20, pady=12)
        tk.Label(header, text="🏷️ 硬件资产参数校准与录入", font=("Segoe UI", 12, "bold"), fg="#38BDF8", bg="#0F172A").pack(anchor="w")
        tk.Label(header, text="专为硬件管理者设计：在此校准真实出厂SN、物理网卡MAC及企业资产编号，保存后将双向永久同步。", font=("Segoe UI", 8), fg="#94A3B8", bg="#0F172A", wraplength=460, justify="left").pack(anchor="w", pady=2)

        form = tk.Frame(dlg, bg="#1E293B", padx=16, pady=16, relief="flat")
        form.pack(fill="both", expand=True, padx=20, pady=4)

        # Android ID (read only)
        tk.Label(form, text=f"系统设备码 (Android ID): {current_android_id or '--'}", font=("Segoe UI", 8), fg="#64748B", bg="#1E293B").pack(anchor="w", pady=2)
        tk.Label(form, text=f"当前序列号探测来源: {serial_source}", font=("Segoe UI", 8), fg="#64748B", bg="#1E293B").pack(anchor="w", pady=2)

        # Serial
        tk.Label(form, text="硬件序列号 (Serial / SN):", font=("Segoe UI", 9, "bold"), fg="#F8FAFC", bg="#1E293B").pack(anchor="w", pady=(8, 2))
        ent_sn = tk.Entry(form, font=("Consolas", 10), bg="#0F172A", fg="#F59E0B", insertbackground="#FFF", relief="flat")
        ent_sn.pack(fill="x", ipady=4)
        ent_sn.insert(0, current_serial)

        # MAC
        mac_box = tk.Frame(form, bg="#1E293B")
        mac_box.pack(fill="x", pady=(10, 2))
        tk.Label(mac_box, text="物理网卡地址 (MAC):", font=("Segoe UI", 9, "bold"), fg="#F8FAFC", bg="#1E293B").pack(side="left")
        if arp_mac:
            def use_arp_mac():
                ent_mac.delete(0, "end")
                ent_mac.insert(0, arp_mac)
            tk.Button(mac_box, text=f"填入ARP探测真实MAC ({arp_mac})", font=("Segoe UI", 7, "bold"), bg="#0284C7", fg="#FFF", relief="flat", padx=6, pady=1, cursor="hand2", command=use_arp_mac).pack(side="right")

        ent_mac = tk.Entry(form, font=("Consolas", 10), bg="#0F172A", fg="#38BDF8", insertbackground="#FFF", relief="flat")
        ent_mac.pack(fill="x", ipady=4)
        ent_mac.insert(0, current_mac)

        # Asset Tag
        tk.Label(form, text="企业资产编号 (Asset Tag，如 IT-PAD-001):", font=("Segoe UI", 9, "bold"), fg="#F8FAFC", bg="#1E293B").pack(anchor="w", pady=(10, 2))
        ent_asset = tk.Entry(form, font=("Consolas", 10), bg="#0F172A", fg="#10B981", insertbackground="#FFF", relief="flat")
        ent_asset.pack(fill="x", ipady=4)
        ent_asset.insert(0, current_asset)

        # Buttons
        btn_bar = tk.Frame(dlg, bg="#0F172A")
        btn_bar.pack(fill="x", padx=20, pady=14)

        def save_action():
            new_sn = ent_sn.get().strip()
            new_mac = ent_mac.get().strip().upper()
            new_asset = ent_asset.get().strip()

            info["serial"] = new_sn
            info["serialSource"] = "管理员校准"
            info["mac"] = new_mac
            info["macSource"] = "管理员校准"
            info["assetTag"] = new_asset
            dev["deviceInfo"] = info

            self.save_config()
            self.update_treeview()

            # Async send to Android server to sync and persist
            def sync_to_device():
                try:
                    url = f"http://{ip}:{port}/api/device/asset"
                    body = json.dumps({
                        "token": token,
                        "serial": new_sn,
                        "mac": new_mac,
                        "assetTag": new_asset
                    }).encode("utf-8")
                    req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json", "X-Auth-Token": token})
                    with urllib.request.urlopen(req, timeout=4) as resp:
                        self.log(f"✅ 设备 [{dev.get('remark')}] 资产硬件信息已成功同步至受控端！")
                except Exception as e:
                    self.log(f"⚠️ 设备 [{dev.get('remark')}] 本地已保存，受控端同步提示: {e}")

            threading.Thread(target=sync_to_device, daemon=True).start()
            dlg.destroy()
            messagebox.showinfo("保存成功", f"设备 [{dev.get('remark')}] 资产硬件信息已更新并同步！")

        tk.Button(btn_bar, text="💾 保存并同步至设备", font=("Segoe UI", 9, "bold"), bg="#0284C7", fg="#FFF", relief="flat", padx=16, pady=6, cursor="hand2", command=save_action).pack(side="right", padx=4)
        tk.Button(btn_bar, text="取消", font=("Segoe UI", 9), bg="#334155", fg="#FFF", relief="flat", padx=12, pady=6, cursor="hand2", command=dlg.destroy).pack(side="right", padx=4)

    def export_assets_csv(self):
        if not self.devices:
            messagebox.showwarning("提示", "当前没有受控设备数据可供导出！")
            return
        default_name = f"安卓设备资产统计表_{time.strftime('%Y%m%d_%H%M%S')}.csv"
        file_path = filedialog.asksaveasfilename(
            title="导出设备硬件资产统计表 (CSV)",
            defaultextension=".csv",
            initialfile=default_name,
            filetypes=[("CSV 表格文件 (*.csv)", "*.csv"), ("所有文件 (*.*)", "*.*")]
        )
        if not file_path:
            return

        try:
            with open(file_path, "w", newline="", encoding="utf-8-sig") as f:
                writer = csv.writer(f)
                writer.writerow([
                    "序号",
                    "设备备注",
                    "在线状态",
                    "IP地址",
                    "端口",
                    "物理MAC地址",
                    "MAC来源说明",
                    "硬件序列号(SN)",
                    "序列号来源",
                    "系统标识(Android ID)",
                    "资产编号",
                    "设备型号",
                    "安卓系统版本",
                    "电池电量",
                    "充电状态",
                    "授权状态",
                    "最后响应时间"
                ])
                for idx, (k, dev) in enumerate(self.devices.items(), start=1):
                    info = dev.get("deviceInfo", {})
                    online_str = "在线" if dev.get("online") else "离线"
                    charging_str = "充电中" if info.get("isCharging") else "未充电"
                    bat_str = f"{info.get('batteryLevel', '')}%" if info.get("batteryLevel") is not None else "--"
                    last_seen_str = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(dev.get("last_seen", time.time())))
                    writer.writerow([
                        idx,
                        dev.get("remark", ""),
                        online_str,
                        dev.get("ip", ""),
                        dev.get("port", 8888),
                        info.get("mac", ""),
                        info.get("macSource", "ARP物理探测/系统"),
                        info.get("serial", ""),
                        info.get("serialSource", "硬件属性/系统"),
                        info.get("androidId", ""),
                        info.get("assetTag", ""),
                        info.get("model", ""),
                        info.get("androidVersion", ""),
                        bat_str,
                        charging_str,
                        "已绑定永久信任" if dev.get("token") else "未绑定",
                        last_seen_str
                    ])
            self.log(f"📊 已成功将 {len(self.devices)} 台设备的硬件资产数据导出至:\n{file_path}")
            messagebox.showinfo("导出成功", f"成功导出 {len(self.devices)} 台设备资产清单！\n文件已保存至:\n{file_path}\n(支持直接使用 Excel 双击打开查看)")
        except Exception as e:
            messagebox.showerror("导出失败", f"导出文件时发生异常: {e}")

    def show_device_details(self):
        sel_items = self.tree.selection()
        if not sel_items:
            messagebox.showwarning("提示", "请在列表中先选中需要查看详情的设备！")
            return
        key = sel_items[0]
        dev = self.devices.get(key)
        if not dev:
            return

        info = dev.get("deviceInfo", {})
        details = (
            f"设备备注: {dev.get('remark')}\n"
            f"网络地址: {dev.get('ip')}:{dev.get('port')}\n"
            f"当前状态: {'🟢 在线' if dev.get('online') else '🔴 离线'}\n"
            f"授权状态: {'已绑定永久密钥 (免除重复绑定)' if dev.get('token') else '未绑定'}\n"
            f"----------------------------------------\n"
            f"资产编号 (Asset Tag): {info.get('assetTag') or '(未设置)'}\n"
            f"物理 MAC 地址: {info.get('mac', '--')}\n"
            f"  └ MAC 来源: {info.get('macSource', '局域网ARP物理层直采')}\n"
            f"硬件唯一序列号: {info.get('serial', '--')}\n"
            f"  └ 序列号来源: {info.get('serialSource', '底层硬件属性/系统识别')}\n"
            f"系统设备码 (Android ID): {info.get('androidId', '--')}\n"
            f"设备硬件型号: {info.get('model', '--')}\n"
            f"安卓操作系统: {info.get('androidVersion', '--')}\n"
            f"电池电量: {info.get('batteryLevel', '--')}%\n"
            f"充电状态: {'⚡ 充电中' if info.get('isCharging') else '未充电'}"
        )
        messagebox.showinfo("设备硬件参数详情", details)

    def remove_selected(self):
        sel_items = self.tree.selection()
        if not sel_items:
            messagebox.showwarning("提示", "请在列表中选中要移除的设备！")
            return
        if messagebox.askyesno("确认解绑并移除", f"确定从管理列表中解绑并移除选中的 {len(sel_items)} 台设备吗？\n移除后，双方信任绑定将被解除，如需再次控制须重新输入PIN码。"):
            for key in sel_items:
                if key in self.devices:
                    dev = self.devices[key]
                    threading.Thread(target=self._async_unbind_device, args=(dev,), daemon=True).start()
                    del self.devices[key]
            self.save_config()
            self.update_treeview()
            self.log(f"已解绑并移除选中的 {len(sel_items)} 台设备。")

    def _async_unbind_device(self, dev):
        try:
            ip = dev.get("ip")
            port = dev.get("port", 8888)
            token = dev.get("token")
            if token:
                url = f"http://{ip}:{port}/api/unbind"
                payload = json.dumps({"token": token}).encode("utf-8")
                req = urllib.request.Request(url, data=payload, headers={"Content-Type": "application/json"})
                with urllib.request.urlopen(req, timeout=3):
                    pass
        except:
            pass

    # ==================== Command Execution & History Recording ====================

    def batch_send_cmd(self, action, params=None, display_title=None):
        target_keys = self.get_selected_device_keys()
        if not target_keys:
            messagebox.showwarning("提示", "请至少勾选一台设备后再发送控制命令！")
            return

        disp_name = display_title or f"命令: {action}"
        self.log(f"🚀 准备向 {len(target_keys)} 台已勾选设备批量下发 [{disp_name}]...")
        threading.Thread(
            target=self._execute_batch_concurrently,
            args=(target_keys, action, params or {}, disp_name),
            daemon=True
        ).start()

    def _execute_batch_concurrently(self, target_keys, action, params, disp_name, is_auto=False):
        futures = {}
        target_names = []
        for key in target_keys:
            dev = self.devices.get(key)
            if dev:
                name = dev.get("remark", dev.get("ip"))
                target_names.append(name)
                fut = self.executor.submit(self._send_single_cmd, dev, action, params, is_auto)
                futures[fut] = (key, name)

        success_count = 0
        fail_count = 0
        response_details = []

        for fut in as_completed(futures):
            key, name = futures[fut]
            ok, msg = fut.result()
            if ok:
                success_count += 1
            else:
                fail_count += 1
            response_details.append({
                "device": name,
                "address": key,
                "success": ok,
                "message": msg,
                "timestamp": time.strftime("%H:%M:%S")
            })

        summary = f"成功 {success_count} 台 / 失败 {fail_count} 台"
        cmd_type_label = "自动命令" if is_auto else "批量命令"
        self.after(0, lambda: self.log(f"🏁 {cmd_type_label} [{disp_name}] 执行完成: {summary}"))

        # Append to Persistent History
        history_item = {
            "id": int(time.time() * 1000),
            "time": time.strftime("%Y-%m-%d %H:%M:%S"),
            "action": disp_name,
            "action_raw": action,
            "params": params,
            "targets_count": len(target_keys),
            "targets": ", ".join(target_names),
            "success_count": success_count,
            "fail_count": fail_count,
            "summary": summary,
            "details": response_details
        }
        self.history.insert(0, history_item)
        if len(self.history) > 200:
            self.history = self.history[:200]
        
        self.after(0, self.save_history)
        self.after(0, self.update_history_tree)
        self.after(0, self.update_treeview)

        # Automatic volume restoration when ringing finishes
        if action == "ring" and not is_auto and success_count > 0:
            duration = params.get("duration", 3)
            def auto_restore_volume():
                time.sleep(duration + 0.5)
                valid_keys = [k for k in target_keys if k in self.devices]
                if valid_keys:
                    self._execute_batch_concurrently(
                        valid_keys,
                        "volume",
                        {"percent": 10},
                        "音量调整到10%",
                        is_auto=True
                    )
            threading.Thread(target=auto_restore_volume, daemon=True).start()
        elif action == "ring_stop" and not is_auto:
            def auto_stop_restore():
                time.sleep(0.3)
                valid_keys = [k for k in target_keys if k in self.devices]
                if valid_keys:
                    self._execute_batch_concurrently(
                        valid_keys,
                        "volume",
                        {"percent": 10},
                        "音量调整到10%",
                        is_auto=True
                    )
            threading.Thread(target=auto_stop_restore, daemon=True).start()

    def _send_single_cmd(self, dev, action, params, is_auto=False):
        ip = dev.get("ip")
        port = dev.get("port", 8888)
        token = dev.get("token")
        name = dev.get("remark", ip)

        if not token:
            err_msg = "未绑定认证密钥，跳过命令"
            if not is_auto:
                self.after(0, lambda: self.log(f"⚠️ [{name}] {err_msg}"))
            return False, err_msg

        url = f"http://{ip}:{port}/api/command"
        payload = {"token": token, "action": action}
        if params:
            payload.update(params)

        data = json.dumps(payload).encode("utf-8")
        headers = {
            "Content-Type": "application/json",
            "X-Auth-Token": token
        }

        # Retry up to 2 times with 8s timeout to ensure locked/sleeping phones respond smoothly!
        last_err = ""
        for attempt in range(2):
            try:
                req = urllib.request.Request(url, data=data, headers=headers)
                with urllib.request.urlopen(req, timeout=8) as response:
                    res = json.loads(response.read().decode("utf-8"))
                    msg = res.get("message", "成功")
                    if not is_auto:
                        self.after(0, lambda: self.log(f"✅ [{name}] 响应: {msg}"))
                    return True, msg
            except urllib.error.HTTPError as e:
                err_bytes = e.read().decode("utf-8", errors="ignore")
                try:
                    res_json = json.loads(err_bytes)
                    msg = res_json.get("message", f"HTTP {e.code}")
                except:
                    msg = f"HTTP {e.code}: {e.reason}"
                if not is_auto:
                    self.after(0, lambda: self.log(f"❌ [{name}] 授权失败: {msg}"))
                return False, msg
            except Exception as e:
                last_err = str(e)
                if attempt == 0:
                    time.sleep(0.6)
                    continue

        if not is_auto:
            self.after(0, lambda: self.log(f"❌ [{name}] 发送失败: {last_err}"))
        return False, last_err

    def on_batch_notify(self):
        title = self.ent_batch_title.get().strip() or "来自电脑端的提醒"
        msg = self.ent_batch_msg.get().strip() or "批量弹窗通知"
        self.batch_send_cmd(
            "notify",
            {"title": title, "message": msg},
            f"💬 批量弹窗通知: {title}"
        )

    def trigger_refresh_all(self):
        self.log("正在并行刷新所有设备在线状态与电量...")
        threading.Thread(target=self._poll_all_devices, daemon=True).start()

    # ==================== Heartbeat & UDP IP Discovery Loops ====================

    def udp_listener_loop(self):
        sock = None
        while self.heartbeat_running:
            if not sock:
                try:
                    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                    sock.bind(("", 8889))
                    sock.settimeout(2.0)
                except Exception:
                    time.sleep(2.0)
                    continue

            try:
                data, addr = sock.recvfrom(4096)
                if data:
                    text = data.decode("utf-8", errors="ignore")
                    self._handle_udp_announcement(text, addr[0])
            except socket.timeout:
                continue
            except Exception:
                time.sleep(1.0)

        if sock:
            try:
                sock.close()
            except:
                pass

    def _handle_udp_announcement(self, text, sender_ip):
        try:
            payload = json.loads(text)
        except Exception:
            return

        msg_type = payload.get("type")
        event = payload.get("event")
        if msg_type != "ip_announce" and event not in ("ip_changed", "ip_response"):
            return

        new_ip = payload.get("newIp") or sender_ip
        port = int(payload.get("port", 8888))
        tokens = payload.get("tokens", [])
        if not isinstance(tokens, list):
            tokens = [tokens] if tokens else []
        serial = payload.get("serial")
        mac = payload.get("mac")
        android_id = payload.get("androidId")
        model = payload.get("model", "Android")

        new_key = f"{new_ip}:{port}"

        # Check if this matches any existing device
        matched_key = None
        matched_dev = None

        # 1. Match by cryptographic token (highest priority)
        for k, dev in list(self.devices.items()):
            dev_token = dev.get("token")
            if dev_token and (dev_token in tokens or dev_token == payload.get("token")):
                matched_key = k
                matched_dev = dev
                break

        # 2. Match by hardware serial (if non-empty & valid)
        if not matched_dev and serial and serial != "--":
            for k, dev in list(self.devices.items()):
                dev_serial = dev.get("deviceInfo", {}).get("serial")
                if dev_serial and dev_serial == serial:
                    matched_key = k
                    matched_dev = dev
                    break

        # 3. Match by android_id
        if not matched_dev and android_id:
            for k, dev in list(self.devices.items()):
                dev_aid = dev.get("deviceInfo", {}).get("androidId")
                if dev_aid and dev_aid == android_id:
                    matched_key = k
                    matched_dev = dev
                    break

        # 4. Match by physical MAC
        if not matched_dev and mac and mac != "02:00:00:00:00:00" and not mac.startswith("FE:"):
            for k, dev in list(self.devices.items()):
                dev_mac = dev.get("deviceInfo", {}).get("mac")
                if dev_mac and dev_mac == mac:
                    matched_key = k
                    matched_dev = dev
                    break

        if matched_dev:
            if matched_key != new_key:
                # IP CHANGED! Migrate immediately
                dev = self.devices.pop(matched_key)
                dev["id"] = new_key
                dev["ip"] = new_ip
                dev["port"] = port
                dev["online"] = True
                dev["last_seen"] = time.time()
                dev["fail_count"] = 0
                if "deviceInfo" not in dev:
                    dev["deviceInfo"] = {}
                dev["deviceInfo"]["model"] = model
                if serial and serial != "--": dev["deviceInfo"]["serial"] = serial
                if mac and mac != "02:00:00:00:00:00": dev["deviceInfo"]["mac"] = mac
                if android_id: dev["deviceInfo"]["androidId"] = android_id
                self.devices[new_key] = dev
                self.after(0, self.save_config)
                self.after(0, self.update_treeview)
                remark = dev.get("remark", "设备")
                self.after(0, lambda: self.log(
                    f"🔄 [动态IP自适应] 接收到受控端变更通知: 设备 [{remark}] IP 由 {matched_key} 变更为 {new_key}，已无感自动更新，无需重新绑定！"
                ))
            else:
                matched_dev["online"] = True
                matched_dev["last_seen"] = time.time()
                matched_dev["fail_count"] = 0
                self.after(0, self.update_treeview)

    def _match_and_auto_migrate(self, target_ip, port, data, source_tag="后台自适应扫描"):
        new_key = f"{target_ip}:{port}"
        serial = data.get("serial")
        mac = data.get("mac")
        android_id = data.get("androidId")
        model = data.get("model", "Android")
        tokens = data.get("tokens", [])
        if not isinstance(tokens, list):
            tokens = [tokens] if tokens else []

        matched_key = None
        matched_dev = None

        # 1. Match by token
        for k, dev in list(self.devices.items()):
            dev_token = dev.get("token")
            if dev_token and (dev_token in tokens or dev_token == data.get("token")):
                matched_key = k
                matched_dev = dev
                break

        # 2. Match by serial
        if not matched_dev and serial and serial != "--":
            for k, dev in list(self.devices.items()):
                dev_serial = dev.get("deviceInfo", {}).get("serial")
                if dev_serial and dev_serial == serial:
                    matched_key = k
                    matched_dev = dev
                    break

        # 3. Match by androidId
        if not matched_dev and android_id:
            for k, dev in list(self.devices.items()):
                dev_aid = dev.get("deviceInfo", {}).get("androidId")
                if dev_aid and dev_aid == android_id:
                    matched_key = k
                    matched_dev = dev
                    break

        # 4. Match by physical MAC
        if not matched_dev and mac and mac != "02:00:00:00:00:00" and not mac.startswith("FE:"):
            for k, dev in list(self.devices.items()):
                dev_mac = dev.get("deviceInfo", {}).get("mac")
                if dev_mac and dev_mac == mac:
                    matched_key = k
                    matched_dev = dev
                    break

        if matched_dev:
            if matched_key != new_key:
                # Migrate IP
                dev = self.devices.pop(matched_key)
                dev["id"] = new_key
                dev["ip"] = target_ip
                dev["port"] = port
                dev["online"] = True
                dev["last_seen"] = time.time()
                dev["fail_count"] = 0
                if "deviceInfo" not in dev:
                    dev["deviceInfo"] = {}
                dev["deviceInfo"]["model"] = model
                if serial and serial != "--": dev["deviceInfo"]["serial"] = serial
                if mac and mac != "02:00:00:00:00:00": dev["deviceInfo"]["mac"] = mac
                if android_id: dev["deviceInfo"]["androidId"] = android_id
                self.devices[new_key] = dev
                self.after(0, self.save_config)
                self.after(0, self.update_treeview)
                remark = dev.get("remark", target_ip)
                self.after(0, lambda: self.log(
                    f"🔄 [动态IP自适应] {source_tag}: 发现设备 [{remark}] IP 由 {matched_key} 迁移至 {new_key}，状态已自动恢复在线！"
                ))
            else:
                if not matched_dev.get("online", False):
                    matched_dev["online"] = True
                    matched_dev["fail_count"] = 0
                    matched_dev["last_seen"] = time.time()
                    self.after(0, self.update_treeview)

    def _auto_background_subnet_discovery(self):
        if getattr(self, "auto_discovery_running", False):
            return
        self.auto_discovery_running = True
        try:
            subnets = set()
            for dev in self.devices.values():
                ip = dev.get("ip", "")
                parts = ip.split(".")
                if len(parts) == 4:
                    subnets.add(f"{parts[0]}.{parts[1]}.{parts[2]}")

            local_hint = self.get_local_ip_hint()
            local_parts = local_hint.split(".")
            if len(local_parts) == 4:
                subnets.add(f"{local_parts[0]}.{local_parts[1]}.{local_parts[2]}")

            for subnet in subnets:
                port = 8888
                with ThreadPoolExecutor(max_workers=35) as sweep_exec:
                    futures = {}
                    for host in range(1, 255):
                        target_ip = f"{subnet}.{host}"
                        fut = sweep_exec.submit(self._check_receiver_port, target_ip, port)
                        futures[fut] = target_ip

                    for fut in as_completed(futures):
                        target_ip = futures[fut]
                        try:
                            is_active, data = fut.result()
                            if is_active and data:
                                self._match_and_auto_migrate(target_ip, port, data, source_tag="后台自适应扫描")
                        except Exception:
                            pass
        except Exception:
            pass
        finally:
            self.auto_discovery_running = False

    def _broadcast_who_has(self, dev):
        try:
            token = dev.get("token", "")
            serial = dev.get("deviceInfo", {}).get("serial", "")
            android_id = dev.get("deviceInfo", {}).get("androidId", "")
            payload = json.dumps({
                "type": "who_has",
                "token": token,
                "serial": serial,
                "androidId": android_id
            }).encode("utf-8")

            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
                s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                s.sendto(payload, ("255.255.255.255", 8889))
        except Exception:
            pass

    def heartbeat_loop(self):
        cycle = 0
        while self.heartbeat_running:
            self._poll_all_devices()
            cycle += 1

            # Check if any bound device is currently offline
            has_offline_bound_dev = any(
                dev.get("token") and not dev.get("online", False)
                for dev in self.devices.values()
            )

            # If any bound device is offline, or periodically every 5 cycles (~20s):
            # run background subnet auto-discovery so IP changes are detected dynamically without clicking!
            if has_offline_bound_dev or (cycle % 5 == 0):
                threading.Thread(target=self._auto_background_subnet_discovery, daemon=True).start()

            # Also broadcast who_has via UDP
            if has_offline_bound_dev and (cycle % 2 == 0):
                for k, dev in list(self.devices.items()):
                    if dev.get("token") and not dev.get("online", False):
                        self._broadcast_who_has(dev)

            time.sleep(4.0)

    def _poll_all_devices(self):
        device_keys = list(self.devices.keys())
        if not device_keys:
            return

        futures = {}
        for key in device_keys:
            dev = self.devices.get(key)
            if dev:
                fut = self.executor.submit(self._check_single_status, dev)
                futures[fut] = key

        updated = False
        for fut in as_completed(futures):
            key = futures[fut]
            try:
                is_online, info = fut.result()
                if key in self.devices:
                    dev = self.devices[key]
                    if dev.get("online") != is_online:
                        dev["online"] = is_online
                        updated = True
                    if info:
                        dev["deviceInfo"] = info
                        updated = True
            except:
                pass

        if updated:
            self.after(0, self.update_treeview)

    def _check_single_status(self, dev):
        ip = dev.get("ip")
        port = dev.get("port", 8888)
        token = dev.get("token")

        urls_to_try = []
        if token:
            urls_to_try.append(f"http://{ip}:{port}/api/status?token={token}")
        urls_to_try.append(f"http://{ip}:{port}/api/ping")

        for url in urls_to_try:
            try:
                req = urllib.request.Request(url)
                if token:
                    req.add_header("X-Auth-Token", token)
                with urllib.request.urlopen(req, timeout=5.0) as response:
                    if response.status == 200:
                        data = json.loads(response.read().decode("utf-8"))
                        dev_info = dev.get("deviceInfo", {})
                        if "batteryLevel" in data:
                            dev_info["batteryLevel"] = data.get("batteryLevel")
                        if "isCharging" in data:
                            dev_info["isCharging"] = data.get("isCharging")
                        if "model" in data:
                            dev_info["model"] = data.get("model")
                        if "serial" in data:
                            dev_info["serial"] = data.get("serial")
                        if "mac" in data and data.get("mac") != "02:00:00:00:00:00":
                            dev_info["mac"] = data.get("mac")
                        if "androidId" in data:
                            dev_info["androidId"] = data.get("androidId")
                        if "serialSource" in data:
                            dev_info["serialSource"] = data.get("serialSource")
                        if "macSource" in data:
                            dev_info["macSource"] = data.get("macSource")
                        if "assetTag" in data:
                            dev_info["assetTag"] = data.get("assetTag")

                        # If MAC is restricted (02:00:00:00:00:00) or missing, resolve link-layer MAC via Windows ARP!
                        current_mac = dev_info.get("mac", "")
                        if not current_mac or current_mac == "02:00:00:00:00:00" or current_mac.startswith("FE:"):
                            arp_mac = self.resolve_mac_via_arp(ip)
                            if arp_mac:
                                dev_info["mac"] = arp_mac
                                dev_info["macSource"] = "局域网ARP物理层直采 (真实网卡MAC)"

                        dev["fail_count"] = 0
                        return True, dev_info
            except Exception:
                continue

        # Prevent transient lock-screen Wi-Fi power-save sleep delay from flickering offline
        fails = dev.get("fail_count", 0) + 1
        dev["fail_count"] = fails
        if fails < 3 and dev.get("online", False):
            return True, dev.get("deviceInfo")
        return False, None

    # ==================== Tab 2: History UI & Operations ====================

    def update_history_tree(self):
        self.history_tree.delete(*self.history_tree.get_children())
        for item in self.history:
            item_id = str(item.get("id"))
            vals = (
                item.get("time"),
                item.get("action"),
                item.get("targets_count"),
                item.get("targets"),
                item.get("summary")
            )
            self.history_tree.insert("", "end", iid=item_id, values=vals)

    def on_history_select(self, event):
        sel = self.history_tree.selection()
        if not sel:
            return
        item_id = sel[0]
        match = next((x for x in self.history if str(x.get("id")) == item_id), None)
        if match:
            detail_str = (
                f"【执行时间】: {match.get('time')}\n"
                f"【命令名称】: {match.get('action')}\n"
                f"【原始指令】: action={match.get('action_raw')}, params={json.dumps(match.get('params'), ensure_ascii=False)}\n"
                f"【执行统计】: {match.get('summary')}\n"
                f"【目标设备】: {match.get('targets')}\n"
                f"---------------- 各设备执行详细回执 ----------------\n"
            )
            for d in match.get("details", []):
                status_symbol = "✅" if d.get("success") else "❌"
                detail_str += f"{status_symbol} [{d.get('device')}] ({d.get('address')}): {d.get('message')} @ {d.get('timestamp')}\n"
            
            self.txt_history_detail.delete("1.0", "end")
            self.txt_history_detail.insert("end", detail_str)

    def rerun_selected_history(self):
        sel = self.history_tree.selection()
        if not sel:
            messagebox.showwarning("提示", "请在历史列表中先选中一条要重放的命令！")
            return
        item_id = sel[0]
        match = next((x for x in self.history if str(x.get("id")) == item_id), None)
        if not match:
            return

        action = match.get("action_raw")
        params = match.get("params", {})
        disp_name = match.get("action")

        if messagebox.askyesno("确认重新下发", f"确定向当前所有已勾选的在线设备重新执行：\n[{disp_name}] 吗？"):
            self.notebook.select(self.tab_devices)
            self.batch_send_cmd(action, params, f"🔁 重放: {disp_name}")

    def load_history_to_inputs(self):
        sel = self.history_tree.selection()
        if not sel:
            messagebox.showwarning("提示", "请在历史列表中先选中一条记录！")
            return
        item_id = sel[0]
        match = next((x for x in self.history if str(x.get("id")) == item_id), None)
        if not match:
            return

        if match.get("action_raw") == "notify":
            params = match.get("params", {})
            title = params.get("title", "")
            msg = params.get("message", "")
            self.ent_batch_title.delete(0, "end")
            self.ent_batch_title.insert(0, title)
            self.ent_batch_msg.delete(0, "end")
            self.ent_batch_msg.insert(0, msg)
            self.notebook.select(self.tab_devices)
            self.log(f"已将历史弹窗内容载入控制面板: [{title}]")
        else:
            messagebox.showinfo("提示", f"该历史命令为 [{match.get('action')}]，可直接点击【再次执行选中历史命令】进行重发。")

    def export_history_json(self):
        if not self.history:
            messagebox.showinfo("提示", "当前历史记录为空，无需导出。")
            return
        path = filedialog.asksaveasfilename(
            defaultextension=".json",
            filetypes=[("JSON Files", "*.json")],
            initialfile=f"controller_history_{time.strftime('%Y%m%d_%H%M%S')}.json"
        )
        if path:
            try:
                with open(path, "w", encoding="utf-8") as f:
                    json.dump(self.history, f, ensure_ascii=False, indent=2)
                messagebox.showinfo("导出成功", f"历史记录已成功导出至:\n{path}")
            except Exception as e:
                messagebox.showerror("导出失败", str(e))

    def clear_all_history(self):
        if not self.history:
            return
        if messagebox.askyesno("清空确认", "确定清空所有本地保存的命令历史审计记录吗？"):
            self.history = []
            self.save_history()
            self.update_history_tree()
            self.txt_history_detail.delete("1.0", "end")
            self.log("已清空所有本地历史审计记录。")

    # ==================== Persistence (Devices & History) ====================

    def load_config(self):
        self.devices = {}
        if os.path.exists(CONFIG_FILE):
            try:
                with open(CONFIG_FILE, "r", encoding="utf-8") as f:
                    cfg = json.load(f)

                # Restore input field defaults if remembered
                if "last_inputs" in cfg and isinstance(cfg["last_inputs"], dict):
                    li = cfg["last_inputs"]
                    if li.get("ip"):
                        self.ent_new_ip.delete(0, "end")
                        self.ent_new_ip.insert(0, li["ip"])
                    if li.get("port"):
                        self.ent_new_port.delete(0, "end")
                        self.ent_new_port.insert(0, str(li["port"]))
                    if li.get("remark"):
                        self.ent_new_remark.delete(0, "end")
                        self.ent_new_remark.insert(0, li["remark"])

                # Check multi-device schema
                if "device_list" in cfg and isinstance(cfg["device_list"], list):
                    for d in cfg["device_list"]:
                        key = f"{d.get('ip')}:{d.get('port', 8888)}"
                        self.devices[key] = d
                elif "last_ip" in cfg:
                    # Legacy config upgrade
                    key = f"{cfg.get('last_ip')}:{cfg.get('last_port', 8888)}"
                    self.devices[key] = {
                        "id": key,
                        "ip": cfg.get("last_ip"),
                        "port": cfg.get("last_port", 8888),
                        "pin": "",
                        "token": cfg.get("token", ""),
                        "remark": "受控手机A",
                        "online": False,
                        "selected": True,
                        "deviceInfo": cfg.get("deviceInfo", {})
                    }
                self.log(f"已成功载入 {len(self.devices)} 台已知设备记忆（启动自动连线与心跳监控，无需重新添加）。")
            except Exception as e:
                self.log(f"载入配置文件异常: {e}")

        self.update_treeview()

    def save_config(self):
        is_zoomed = False
        try:
            is_zoomed = (self.state() == "zoomed")
        except Exception:
            pass

        geo = getattr(self, "_last_normal_geometry", None)
        if not geo:
            try:
                geo = self.geometry()
            except Exception:
                geo = "1060x780"

        cfg = {
            "version": "2.2",
            "window": {
                "geometry": geo,
                "is_maximized": is_zoomed
            },
            "last_inputs": {
                "ip": self.ent_new_ip.get().strip(),
                "port": self.ent_new_port.get().strip(),
                "remark": self.ent_new_remark.get().strip()
            },
            "device_list": list(self.devices.values())
        }
        try:
            with open(CONFIG_FILE, "w", encoding="utf-8") as f:
                json.dump(cfg, f, ensure_ascii=False, indent=2)
        except Exception as e:
            self.log(f"保存配置文件失败: {e}")

    def load_history(self):
        self.history = []
        if os.path.exists(HISTORY_FILE):
            try:
                with open(HISTORY_FILE, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    if isinstance(data, list):
                        self.history = data
                self.log(f"已成功恢复 {len(self.history)} 条历史命令审计记录。")
            except Exception as e:
                self.log(f"恢复历史记录异常: {e}")
        self.update_history_tree()

    def save_history(self):
        try:
            with open(HISTORY_FILE, "w", encoding="utf-8") as f:
                json.dump(self.history, f, ensure_ascii=False, indent=2)
        except Exception as e:
            self.log(f"保存历史记录失败: {e}")

if __name__ == "__main__":
    app = Win11MultiDeviceControllerApp()
    app.mainloop()
