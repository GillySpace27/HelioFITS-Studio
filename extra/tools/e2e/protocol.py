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
def sh(cmd, **kw): return subprocess.run(cmd, shell=True, capture_output=True, text=True, **kw).stdout
def samp(mtype, params):
    home = open(f"{S}/{name}.home").read().strip()
    return sh(f"python3 {SD}/samp.py {home} {mtype} '{json.dumps(params)}' 2>&1 | tail -1")
def snap(tag):
    pid = open(f"{S}/{name}.pid").read().strip()
    d = {"t": time.strftime("%H:%M:%S"), "tag": tag}
    fp = sh(f"footprint {pid} 2>/dev/null | grep -m1 Footprint")
    d["footprint"] = fp.split("Footprint:")[1].split("(")[0].strip() if "Footprint" in fp else "?"
    hi = sh(f"{JCMD} {pid} GC.heap_info 2>/dev/null | grep -m1 garbage")
    m = re.search(r"committed (\d+)K, used (\d+)K", hi)
    d["heap_used_MB"] = int(m.group(2)) // 1024 if m else None
    d["cpu"] = sh(f"ps -p {pid} -o %cpu= ").strip()
    open(f"{out}/snapshots.jsonl", "a").write(json.dumps(d) + "\n")
    print(d, flush=True)
    return d
def jfr(tag, secs):
    pid = open(f"{S}/{name}.pid").read().strip()
    f = f"{out}/{tag}.jfr"
    print(sh(f"{JCMD} {pid} JFR.start name={tag} duration={secs}s filename={f} settings=profile jdk.ObjectAllocationOutsideTLAB#enabled=true 2>&1 | tail -1"), flush=True)
sh(f"E2E_OUT={S} {SD}/launch.sh {wt} {name}")
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
    sh(f"{JCMD} {pid} GC.class_histogram > {out}/histogram.txt 2>&1"); sh(f"footprint {pid} > {out}/footprint.txt 2>&1"); print("probe done", flush=True); sys.exit(0)
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
sh(f"{JCMD} {pid} GC.class_histogram > {out}/histogram.txt 2>&1")
sh(f"{JCMD} {pid} GC.heap_info > {out}/heap_info.txt 2>&1")
sh(f"{JCMD} {pid} Thread.print > {out}/threads.txt 2>&1")
sh(f"footprint {pid} > {out}/footprint.txt 2>&1")
sh(f"vmmap -v {pid} > {out}/vmmap.txt 2>&1")
snap("end")
print("done", flush=True)
