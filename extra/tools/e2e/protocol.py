"""End-to-end memory and latency protocol: launch a worktree's HFStudio.jar on a throwaway home, load a
1291-frame AIA 171 request over SAMP, zoom, record idle and scrub JFRs, and snapshot footprint and heap.
Usage: E2E_OUT=/tmp/out [HFS_JVM_EXTRA=-Djhv.binBudgetMB=150] [ZOOMIN=3] [PROBE=1] python3 protocol.py <name> <worktree>
Needs astropy; defaults the throwaway home to server GSFC (the default IAS may be down). Never launches the installed app.
"""
import subprocess, sys, time, os, re, json, random
SD = os.path.dirname(os.path.abspath(__file__))  # the scripts
S = os.environ.get("E2E_OUT", "/tmp/hfs-e2e-out")  # logs, homes, JFR files, snapshots
os.makedirs(S, exist_ok=True)
name, wt = sys.argv[1], sys.argv[2]
out = f"{S}/{name}"
os.makedirs(out, exist_ok=True)
JCMD = "jcmd"
def sh(*argv, to=None, env=None):  # no shell: argv as given, stderr folded into the output
    r = subprocess.run([str(a) for a in argv], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, env=env)
    if to: open(to, "w").write(r.stdout)
    return r.stdout
def last(text): return (text.strip().splitlines() or [""])[-1]
def first(text, word): return next((l for l in text.splitlines() if word in l), "")
def samp(mtype, params):
    home = open(f"{S}/{name}.home").read().strip()
    return last(sh(sys.executable, f"{SD}/samp.py", home, mtype, json.dumps(params)))
def snap(tag):
    pid = open(f"{S}/{name}.pid").read().strip()
    d = {"t": time.strftime("%H:%M:%S"), "tag": tag}
    fp = first(sh("footprint", pid), "Footprint")
    d["footprint"] = fp.split("Footprint:")[1].split("(")[0].strip() if "Footprint" in fp else "?"
    hi = first(sh(JCMD, pid, "GC.heap_info"), "garbage")
    m = re.search(r"committed (\d+)K, used (\d+)K", hi)
    d["heap_used_MB"] = int(m.group(2)) // 1024 if m else None
    d["cpu"] = sh("ps", "-p", pid, "-o", "%cpu=").strip()
    open(f"{out}/snapshots.jsonl", "a").write(json.dumps(d) + "\n")
    print(d, flush=True)
    return d
def jfr(tag, secs):
    pid = open(f"{S}/{name}.pid").read().strip()
    f = f"{out}/{tag}.jfr"
    print(last(sh(JCMD, pid, "JFR.start", f"name={tag}", f"duration={secs}s", f"filename={f}", "settings=profile", "jdk.ObjectAllocationOutsideTLAB#enabled=true")), flush=True)
sh(f"{SD}/launch.sh", wt, name, env={**os.environ, "E2E_OUT": S})
pid = open(f"{S}/{name}.pid").read().strip()
home = open(f"{S}/{name}.home").read().strip()
time.sleep(45)  # sources load, SAMP hub up
print(samp("jhv.load.request", {"url": f"file://{SD}/req.json"}), flush=True)
t0 = time.time()
while time.time() - t0 < 600:
    log = open(f"{S}/{name}.log").read()
    if log.count('"frames"') >= 2: break
    time.sleep(5)
print("request answered after", int(time.time() - t0), "s;", log.count('"frames"'), "pieces", flush=True)
snap("loaded-request")
print(samp("jhv.view.zoom-1-to-1", {}), flush=True)
for _ in range(int(os.environ.get("ZOOMIN", "0"))):
    samp("jhv.view.zoom-in", {}); time.sleep(0.5)
PROBE = os.environ.get("PROBE") == "1"
for i in range(8):
    time.sleep(15); snap(f"download-{(i+1)*15}s")
snap("before-idle")
if PROBE:
    sh(JCMD, pid, "GC.class_histogram", to=f"{out}/histogram.txt"); sh("footprint", pid, to=f"{out}/footprint.txt"); print("probe done", flush=True); sys.exit(0)
jfr("idle", 60); time.sleep(65); snap("after-idle")
jfr("scrub", 60)
random.seed(7)
from astropy.samp import SAMPIntegratedClient
os.environ["SAMP_HUB"] = "std-lockurl:file://" + home + "/.samp"
c = SAMPIntegratedClient(name="e2e-scrub"); c.connect()
target = [i for i in c.get_registered_clients() if c.get_metadata(i).get("samp.name") == "HFStudio"][0]
t1 = time.time(); n = 0
while time.time() - t1 < 60:
    c.notify(target, {"samp.mtype": "jhv.playback.seek-frame", "samp.params": {"frame": str(random.randrange(0, 1291))}}); n += 1
    time.sleep(0.25)
c.disconnect()
print("scrub seeks:", n, flush=True)
time.sleep(8)
snap("after-scrub")
sh(JCMD, pid, "GC.class_histogram", to=f"{out}/histogram.txt")
sh(JCMD, pid, "GC.heap_info", to=f"{out}/heap_info.txt")
sh(JCMD, pid, "Thread.print", to=f"{out}/threads.txt")
sh("footprint", pid, to=f"{out}/footprint.txt")
sh("vmmap", "-v", pid, to=f"{out}/vmmap.txt")
snap("end")
print("done", flush=True)
