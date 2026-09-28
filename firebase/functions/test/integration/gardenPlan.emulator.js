// End-to-end check of syncGardenPlan and getGardenSignal on the emulators (never production):
//   firebase emulators:exec --only functions,firestore --project demo-sage "node functions/test/integration/gardenPlan.emulator.js"
const assert = require("node:assert/strict");
const PROJECT = process.env.GCLOUD_PROJECT || "demo-sage";
const fn = (name) => `http://127.0.0.1:5001/${PROJECT}/us-central1/${name}`;
async function call(name, body, expectStatus = 200) {
  const res = await fetch(fn(name), { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
  const json = await res.json();
  assert.equal(res.status, expectStatus, `${name}: ${JSON.stringify(json)}`);
  return json;
}
const empty = { plants: [], plantTombstones: [], careLog: [], careLogTombstones: [] };

(async () => {
  const owner = "phone-" + Date.now();
  const ownerToken = (await call("syncGarden", { deviceId: owner, ...empty })).memberToken;
  const desktop = "desktop-" + Date.now();
  const desktopToken = (await call("syncGarden", { deviceId: desktop, gardenId: owner, deviceName: "Desktop app", ...empty })).memberToken;

  // Owner pushes a plan with an image.
  const plan = { paths: [{ id: "p1", zone: "Front", outletX: 0.1, outletY: 0.2, segments: [] }], sunZones: [], irrigationZones: ["Front"], imageHash: "h1", imageWidth: 800, imageHeight: 600 };
  const pushed = await call("syncGardenPlan", { deviceId: owner, gardenId: owner, memberToken: ownerToken, plan, image: "SU1BR0U=" });
  assert.equal(pushed.planRev, 1);

  // A member sees it — image only when their cached hash differs.
  const pulled = await call("syncGardenPlan", { deviceId: desktop, gardenId: owner, memberToken: desktopToken });
  assert.equal(pulled.plan.irrigationZones[0], "Front");
  assert.equal(pulled.image, "SU1BR0U=");
  const cached = await call("syncGardenPlan", { deviceId: desktop, gardenId: owner, memberToken: desktopToken, knownImageHash: "h1" });
  assert.equal(cached.image, null);

  // A member can't overwrite the plan.
  await call("syncGardenPlan", { deviceId: desktop, gardenId: owner, memberToken: desktopToken, plan: { ...plan, irrigationZones: ["Hacked"] } });
  assert.equal((await call("syncGardenPlan", { deviceId: desktop, gardenId: owner, memberToken: desktopToken, knownImageHash: "h1" })).plan.irrigationZones[0], "Front");

  // Re-pushing an identical plan doesn't bump planRev.
  assert.equal((await call("syncGardenPlan", { deviceId: owner, gardenId: owner, memberToken: ownerToken, plan })).planRev, 1);

  // The signal endpoint reports revs to members, and refuses bad tokens.
  const signal = await call("getGardenSignal", { deviceId: desktop, gardenId: owner, memberToken: desktopToken });
  assert.equal(signal.planRev, 1);
  assert.ok(signal.rev >= 1);
  await call("getGardenSignal", { deviceId: desktop, gardenId: owner, memberToken: "wrong" }, 403);

  console.log("gardenPlan emulator integration: all checks passed");
})().catch((e) => { console.error(e); process.exit(1); });
