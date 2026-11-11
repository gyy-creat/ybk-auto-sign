#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
云班课协议探针 —— 在 PC 上先把接口跑通，再决定 App 里怎么判定。

只用标准库，不需要 pip install 任何东西。

用法：
    python ybk_probe.py --account 学号 --password 密码
    python ybk_probe.py --account 学号 --password 密码 --class-id <课程ID>
    python ybk_probe.py --account 学号 --password 密码 --clockin <签到ID>

它干的事：
    1. 登录，打印拿到的 user_id / access_id / access_secret（后两项打码）
    2. list_joined，列出你所有课程（id + 名称）
    3. 每门课查 current_open，把**原始 JSON** 打出来
       —— 这一步是重点：能看清「开签」到底由哪个字段表示
    4. 有 --clockin 时真的提交一次签到，打印返回

把第 3、4 步的输出贴回来，就能把 App 里 CheckinProbe 的判定规则从猜改成确定。
"""

import io
import argparse
import datetime
import hashlib
import hmac
import json
import os
import sys
import urllib.parse
import urllib.request

BASE = "https://api.mosoteach.cn/mssvc/index.php"
LOGIN_URL = "https://www.mosoteach.cn/web/index.php?c=passport&m=account_login"

# 接口编号 → 完整 URL
URLS = {
    1: BASE + "/cc/list_joined",          # GET，无参数
    2: BASE + "/checkin/index",           # POST clazz_course_id & page & role_id
    3: BASE + "/checkin/current_open",    # POST clazz_course_id
    4: BASE + "/cc_clockin/clockin",      # POST cc_id
}

UA = "Dalvik/2.1.0 (Linux; U; Android 8.1.0; ONE A2001 Build/OPM7.181205.001)"
MACHINE = "ONE A2001"
SYSVER = "8.1.0"
APPVER = "5.1.1"

_DAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
_MONS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun",
         "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]


def date_header() -> str:
    """Tue, 11 Nov 2026 09:25:37 GMT+00:00 —— 必须 UTC，且同一次请求只能生成一次。"""
    n = datetime.datetime.now(datetime.timezone.utc)
    return "%s, %02d %s %04d %02d:%02d:%02d GMT+00:00" % (
        _DAYS[n.weekday()], n.day, _MONS[n.month - 1], n.year,
        n.hour, n.minute, n.second,
    )


def sign(api_no: int, access_secret: str, user_id: str, date_str: str, params: str) -> str:
    message = URLS[api_no] + "|" + user_id.upper() + "|" + date_str
    if params:
        message += "|" + hashlib.md5(params.encode("utf-8")).hexdigest().upper()
    return hmac.new(access_secret.encode("utf-8"),
                    message.encode("utf-8"),
                    hashlib.sha1).hexdigest()


def build_headers(api_no, cred, date_str, params):
    h = {
        "User-Agent": UA,
        "X-scheme": "https",
        "X-app-id": "MTANDROID",
        "X-app-version": APPVER,
        "X-dpr": "2.7",
        "X-app-machine": MACHINE,
        "X-app-system-version": SYSVER,
        "Accept": "*/*",
        "Accept-Encoding": "identity",
    }
    if cred:
        h["X-mssvc-access-id"] = cred["access_id"]
        h["X-mssvc-sec-ts"] = date_str
        h["X-mssvc-signature"] = sign(api_no, cred["access_secret"],
                                      cred["user_id"], date_str, params)
    return h


def call(api_no, cred, form: dict | None = None, verbose=True):
    date_str = date_header()          # 只生成一次，签名和 header 共用
    if form is None:
        url = URLS[api_no]
        body = None
        params = ""
    else:
        params = urllib.parse.urlencode(form)     # 保持调用方给的字段顺序
        url = URLS[api_no]
        body = params.encode("utf-8")
    headers = build_headers(api_no, cred, date_str, params)
    if body is not None:
        headers["Content-Type"] = "application/x-www-form-urlencoded; charset=UTF-8"
    req = urllib.request.Request(url, data=body, headers=headers,
                                 method="POST" if body else "GET")
    try:
        with urllib.request.urlopen(req, timeout=25) as r:
            raw = r.read().decode("utf-8", "replace")
            code = r.status
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        code = e.code
    if verbose:
        print("    HTTP %s  %s" % (code, raw[:1200]))
    try:
        return code, json.loads(raw)
    except Exception:
        return code, {"_raw": raw}


def login(account, password):
    form = urllib.parse.urlencode({
        "account_name": account,
        "user_pwd": password,
        "remember_me": "N",
    }).encode()
    req = urllib.request.Request(
        LOGIN_URL, data=form,
        headers={
            "User-Agent": UA,
            "Content-Type": "application/x-www-form-urlencoded",
            "X-app-id": "MTANDROID",
        },
        method="POST")
    with urllib.request.urlopen(req, timeout=25) as r:
        data = json.loads(r.read().decode("utf-8", "replace"))
    user = data.get("user") or {}
    if not user.get("access_id"):
        print("[!] 登录没拿到 access_id，原始返回：")
        print(json.dumps(data, ensure_ascii=False, indent=2)[:2000])
        raise SystemExit(1)
    print("[+] 登录成功 user_id=%s access_id=%s access_secret=%s..."
          % (user["user_id"], user["access_id"], str(user.get("access_secret"))[:6]))
    return {
        "user_id": user["user_id"],
        "access_id": user["access_id"],
        "access_secret": user["access_secret"],
    }


def rows_of(data):
    """兼容 [..] / {rows} / {data} / {list} / {data:{rows}}"""
    if isinstance(data, list):
        return data
    if isinstance(data, dict):
        for k in ("rows", "list", "data", "items", "courses"):
            v = data.get(k)
            if isinstance(v, list):
                return v
            if isinstance(v, dict):
                for k2 in ("rows", "list", "items", "courses"):
                    if isinstance(v.get(k2), list):
                        return v[k2]
    return []


class _Tee:
    """控制台照常打印，同时把全部输出落盘一份，方便直接贴回来。"""
    def __init__(self, path):
        self.f = io.open(path, "w", encoding="utf-8")

    def write(self, s):
        self.f.write(s)
        self.f.flush()

    def flush(self):
        self.f.flush()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--account", required=True)
    ap.add_argument("--password", required=True)
    ap.add_argument("--class-id", help="只查这一门课（云班课的 clazz_course_id）")
    ap.add_argument("--clockin", help="真的对这门课提交一次签到")
    a = ap.parse_args()

    out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "probe_out.txt")
    sys.stdout = _Tee(out_path)
    print("输出同时保存到：%s\n" % out_path)

    cred = login(a.account, a.password)

    print("\n=== 1) 课程列表 cc/list_joined ===")
    _, lst = call(1, cred)
    courses = rows_of(lst)
    for c in courses:
        print("    id=%-10s %s" % (c.get("id") or c.get("clazz_course_id"),
                                  c.get("course_name") or c.get("name")))
    if not courses:
        print("    （空，原始返回见上）")

    targets = courses
    if a.class_id:
        targets = [c for c in courses
                   if str(c.get("id") or c.get("clazz_course_id")) == str(a.class_id)]
        if not targets:
            targets = [{"id": a.class_id, "course_name": "(手动指定)"}]

    for c in targets:
        cid = c.get("id") or c.get("clazz_course_id")
        print("\n=== 2) %s  checkin/index ===" % c.get("course_name"))
        call(2, cred, {"clazz_course_id": cid, "page": 1, "role_id": 2})
        print("=== 3) %s  checkin/current_open ===" % c.get("course_name"))
        _, open_res = call(3, cred, {"clazz_course_id": cid})
        print("    ↑ 这一段就是判定「开没开签」的全部依据，贴回来即可")

    if a.clockin:
        print("\n=== 4) 提交签到 cc_clockin/clockin ===")
        call(4, cred, {"cc_id": a.clockin})
        print("    ↑ 成功/失败的真实返回长这样")


if __name__ == "__main__":
    try:
        main()
    finally:
        try:
            sys.stdout.f.flush()
            print("\n[完成] 把 %s 整个文件发回来即可" % sys.stdout.f.name)
            sys.stdout.f.close()
        except Exception:
            pass
        sys.stdout = sys.__stdout__
