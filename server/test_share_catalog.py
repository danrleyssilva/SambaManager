"""Small share catalog check that also runs on Windows without Linux's pwd module."""
import ast
from pathlib import Path
import re
import types
import unittest
from unittest import mock


def configured_shares_function():
    source = ast.parse(Path(__file__).with_name("samba_password_wsgi.py").read_text(encoding="utf-8"))
    function = next(node for node in source.body
                    if isinstance(node, ast.FunctionDef) and node.name == "configured_shares")
    namespace = {"re": re, "subprocess": mock.Mock(), "logging": mock.Mock()}
    exec(compile(ast.Module(body=[function], type_ignores=[]), "<share-catalog>", "exec"), namespace)
    return namespace["configured_shares"], namespace["subprocess"]


class ShareCatalogTest(unittest.TestCase):
    def test_effective_sections_are_returned_in_order(self):
        read_shares, subprocess = configured_shares_function()
        subprocess.run.return_value = types.SimpleNamespace(stdout="""
[global]
[0 - Faturamento]
    path = /srv/main_storage/0 - Faturamento
[17 - Manutencao]
[Administracao]
[printers]
[26 - Projetos]
""")
        self.assertEqual(read_shares(), ["0 - Faturamento", "17 - Manutencao",
                                         "Administracao", "26 - Projetos"])
        subprocess.run.assert_called_once()

    def test_names_that_cannot_be_mapped_are_excluded(self):
        read_shares, subprocess = configured_shares_function()
        subprocess.run.return_value = types.SimpleNamespace(stdout="[global]\n[ok]\n[bad|name]\n")
        self.assertEqual(read_shares(), ["ok"])


if __name__ == "__main__":
    unittest.main()
