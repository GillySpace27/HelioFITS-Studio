import sys, os, json
from astropy.samp import SAMPIntegratedClient
home, mtype = sys.argv[1], sys.argv[2]
params = json.loads(sys.argv[3]) if len(sys.argv) > 3 else {}
os.environ["SAMP_HUB"] = "std-lockurl:file://" + home + "/.samp"
c = SAMPIntegratedClient(name="e2e")
c.connect()
try:
    ids = [i for i in c.get_registered_clients() if i != c.get_public_id()]
    meta = {i: c.get_metadata(i).get("samp.name") for i in ids}
    target = [i for i in ids if "HFStudio" in str(meta[i]) or "JHV" in str(meta[i]) or "Helio" in str(meta[i])] or ids
    print("clients", meta, flush=True)
    for t in target:
        print(mtype, c.call_and_wait(t, {"samp.mtype": mtype, "samp.params": params}, "20"), flush=True)
finally:
    c.disconnect()
