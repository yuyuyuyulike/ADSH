#!/usr/bin/env python3
"""注释剥离（第 182 轮起实现搬到 kt_source.py，这里只留兼容壳）。

新代码请直接 import kt_source：它同时提供 mask_strings（抹字符串正文、保留模板引用）与
identifiers（词法级标识符流）——死代码扫描真正需要的那一层。
"""
from kt_source import strip_comments  # noqa: F401
