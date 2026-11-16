#!/usr/bin/env python3
"""Validate the real native kotlin-cpp CLI in place, including ANTLR4 + universal syn-kotlin round-trip and C++23 compilation."""
from pathlib import Path
import argparse, hashlib, json, subprocess

ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument("output")
p.add_argument("--binary", default=str(ROOT / "build/bin/macosArm64/releaseExecutable/kotlin-cpp.kexe"))
a = p.parse_args()

BINARY = Path(a.binary).resolve()
OUT = (ROOT / a.output).resolve()
if ROOT / "build" not in OUT.parents:
    p.error("output must be inside this tool project build directory")
OUT.mkdir(parents=True, exist_ok=False)

source = OUT / "SumKernel.kt"
source.write_text(
    "// port-lint: source kernel/SumKernel.kt\n"
    "package kotlinx.coroutines.kernel\n"
    "\n"
    "/** Native CLI UTF-8 smoke: 漢 🌍 with [SumBox]. */\n"
    "public class SumBox(\n"
    "    public val bias: Int,\n"
    ") {\n"
    "    public fun add(a: Int, b: Int): Int = bias + a + b\n"
    "}\n"
)

results = []


def execute(name, command, expected=0, timeout=120):
    r = subprocess.run(list(map(str, command)), capture_output=True, text=True, timeout=timeout, cwd=ROOT)
    (OUT / (name + ".stdout")).write_text(r.stdout)
    (OUT / (name + ".stderr")).write_text(r.stderr)
    results.append({"name": name, "command": list(map(str, command)), "exit": r.returncode})
    print(name, r.returncode, flush=True)
    if r.returncode != expected:
        raise RuntimeError(name + ": " + r.stdout[-1500:] + r.stderr[-1500:])
    return r


kind = execute("file", ["/usr/bin/file", BINARY]).stdout
assert "Mach-O" in kind and "arm64" in kind
linked = execute("linked-libraries", ["/usr/bin/otool", "-L", BINARY]).stdout
assert "libjvm" not in linked.lower() and "libjli" not in linked.lower()
assert "Usage:" in execute("help", [BINARY, "--help"]).stdout

tokens_out = execute("tokens", [BINARY, "tokens", source]).stdout
assert "association\tfun\tToken![fn]\t1" in tokens_out

packets_out = execute("packets", [BINARY, "packets", source]).stdout
assert "comments=2" in packets_out and "kdocLinks=1" in packets_out

roundtrip_out = OUT / "SumKernel.roundtrip.kt"
execute("roundtrip", [BINARY, "roundtrip", source, roundtrip_out])
assert roundtrip_out.read_text() == source.read_text()
before = hashlib.sha256(roundtrip_out.read_bytes()).hexdigest()
execute("refuse-existing", [BINARY, "roundtrip", source, roundtrip_out], 2)
assert before == hashlib.sha256(roundtrip_out.read_bytes()).hexdigest()

invalid = OUT / "Invalid.kt"
invalid.write_text("public fun broken(: Int {\n")
invalid_output = OUT / "Invalid.out.kt"
execute("reject-invalid", [BINARY, "roundtrip", invalid, invalid_output], 2)
assert not invalid_output.exists()
execute("reject-missing", [BINARY, "tokens", OUT / "Missing.kt"], 2)
execute("usage", [BINARY], 2)

cpp_dir = OUT / "cpp_out"
execute("kcpp", [BINARY, "-o", cpp_dir, source])
hpp_file = cpp_dir / "kotlinx/coroutines/kernel/SumKernel.hpp"
cpp_file = cpp_dir / "kotlinx/coroutines/kernel/SumKernel.cpp"
assert hpp_file.exists() and cpp_file.exists()

runner = OUT / "main.cpp"
runner.write_text(
    '#include <cassert>\n'
    '#include <iostream>\n'
    '#include "kotlinx/coroutines/kernel/SumKernel.hpp"\n'
    "int main() {\n"
    "    kotlinx::coroutines::kernel::SumBox box{10};\n"
    "    assert(box.add(2, 3) == 15);\n"
    '    std::cout << "PASS: translated C++23 SumBox\\n";\n'
    "    return 0;\n"
    "}\n"
)
cpp_bin = OUT / "sum_cpp23_check"
execute(
    "compile-translated-cpp23",
    ["/usr/bin/clang++", "-std=c++23", "-Wall", "-Wextra", "-Werror", f"-I{cpp_dir}", cpp_file, runner, "-o", cpp_bin],
    timeout=180,
)
run_stdout = execute("run-translated-cpp23", [cpp_bin]).stdout
assert "PASS: translated C++23 SumBox" in run_stdout

sources = sorted((ROOT / "src/common/commonMain/kotlin/io/github/kotlinmania/kotlincpp").rglob("*.kt")) + sorted(
    (ROOT / "src/native").rglob("*.kt")
)
receipt = {
    "binary": str(BINARY),
    "binary_sha256": hashlib.sha256(BINARY.read_bytes()).hexdigest(),
    "native_file_type": kind.strip(),
    "no_jvm_runtime_linked": True,
    "source_hashes": {str(s.relative_to(ROOT)): hashlib.sha256(s.read_bytes()).hexdigest() for s in sources},
    "checks": results,
    "existing_output_preserved": True,
    "invalid_output_absent": True,
    "installed": False,
}
(OUT / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
print("PASS: actual native CLI, ANTLR4 + syn-kotlin round-trip, and translated C++23 program", flush=True)
