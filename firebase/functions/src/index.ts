import { initializeApp } from "firebase-admin/app";

initializeApp();

export { sageChat } from "./handlers/sageChat";
export { sageAutoFill } from "./handlers/sageAutoFill";
export { redeemPromoCode } from "./handlers/redeemPromoCode";
export { syncEntitlement } from "./handlers/syncEntitlement";
export { verifyPurchase } from "./handlers/verifyPurchase";
