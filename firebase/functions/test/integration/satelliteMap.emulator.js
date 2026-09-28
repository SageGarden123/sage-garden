// Emulator check for satelliteMap (uses a dummy key from functions/.secret.local, so Google rejects it):
//   firebase emulators:exec --only functions,firestore --project demo-sage "node functions/test/integration/satelliteMap.emulator.js"
const assert = require("node:assert/strict");
const PROJECT = process.env.GCLOUD_PROJECT || "demo-sage";
const fn = (name) => `http://127.0.0.1:5001/${PROJECT}/us-central1/${name}`;
async function call(name, body) {
  const res = await fetch(fn(name), { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
  return { status: res.status, json: await res.json() };
}
(async () => {
  const owner = "phone-" + Date.now();
  const token = (await call("syncGarden", { deviceId: owner, plants: [], plantTombstones: [], careLog: [], careLogTombstones: [] })).json.memberToken;
  const view = { lat: -33.87, lng: 151.2, zoom: 19, width: 640, height: 480 };
  assert.equal((await call("satelliteMap", { deviceId: owner, gardenId: owner, memberToken: "wrong", ...view })).status, 403);
  assert.equal((await call("satelliteMap", { deviceId: owner, gardenId: owner, memberToken: token, ...view, width: 5000 })).status, 400);
  const r = await call("satelliteMap", { deviceId: owner, gardenId: owner, memberToken: token, ...view });
  assert.equal(r.status, 502, "a dummy key is rejected by Google and reported as upstream_error");
  assert.equal(r.json.error, "upstream_error");
  console.log("satelliteMap emulator integration: all checks passed");
})().catch((e) => { console.error(e); process.exit(1); });
