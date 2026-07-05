import platform
import os
import lit.formats
from lit.llvm import llvm_config
from lit.llvm.subst import ToolSubst

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ScalaCliTest import ScalaCliTest

config.name = 'ZAOZISTDLIB'
config.test_format = ScalaCliTest(True)
config.suffixes = [".scala", ".sc"]
config.substitutions = [
    ('%SCALAVERSION', config.scala_version),
    ('%RUNCLASSPATH', ':'.join(config.run_classpath)),
    ('%JAVAHOME', config.java_home),
    ('%JAVALIBRARYPATH', ':'.join(config.java_library_path)),
    ('%JAVAOPTS', ' '.join(config.java_opts)),
]
config.test_source_root = os.path.dirname(__file__)

# Pass through environment variables to allow configuration from the runner/CI.
# Proxy variables and COURSIER_CACHE propagate so scala-cli's embedded coursier
# can reach Maven Central via the caller's HTTP proxy and reuse the Mill-
# populated Coursier cache rather than cold-fetching into a new default cache
# directory under $user.home/.cache/coursier.
env_vars_to_pass = [
    "SCALA_CLI_HOME", "JAVA_OPTS",
    "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY",
    "http_proxy", "https_proxy", "all_proxy", "no_proxy",
    "COURSIER_CACHE",
    "NIX_LDFLAGS"
]
for var in env_vars_to_pass:
    if var in os.environ:
        config.environment[var] = os.environ[var]

# Coursier (and the JVM in general) reads proxy from -Dhttp.proxyHost etc.
# JVM properties, not from HTTPS_PROXY env directly. Translate the env-style
# proxy URL into JVM-property JAVA_OPTS so scala-cli's resolver routes through
# it. Without this, scala-cli connects directly to repo1.maven.org (resolved
# to a Cloudflare IP) and stalls in SYN-SENT inside the sandbox.
import urllib.parse
java_opts_extra = []
for env_name, host_prop, port_prop in [
    ("HTTPS_PROXY", "https.proxyHost", "https.proxyPort"),
    ("HTTP_PROXY",  "http.proxyHost",  "http.proxyPort"),
]:
    proxy_url = os.environ.get(env_name)
    if proxy_url:
        parsed = urllib.parse.urlparse(proxy_url)
        if parsed.hostname:
            java_opts_extra.append(f"-D{host_prop}={parsed.hostname}")
        if parsed.port:
            java_opts_extra.append(f"-D{port_prop}={parsed.port}")
if java_opts_extra:
    existing = config.environment.get("JAVA_OPTS", "")
    config.environment["JAVA_OPTS"] = (existing + " " + " ".join(java_opts_extra)).strip()
