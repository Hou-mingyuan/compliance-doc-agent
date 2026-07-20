import { createApp } from "vue";
import { createRouter, createWebHashHistory } from "vue-router";
import App from "./App.vue";
import ToastHost from "./components/ToastHost.vue";
import "./styles.css";

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: "/", redirect: "/documents" },
    { path: "/documents", component: () => import("./views/UploadView.vue"), meta: { title: "文档" } },
    { path: "/reviews", component: () => import("./views/ReviewsView.vue"), meta: { title: "审核" } },
    { path: "/reviews/new/:documentId", component: () => import("./views/ReportView.vue"), meta: { title: "发起审核" } },
    { path: "/reviews/:reviewKey", component: () => import("./views/ReportView.vue"), meta: { title: "审核工作台" } },
    { path: "/remediations", component: () => import("./views/RemediationsView.vue"), meta: { title: "整改" } },
    { path: "/regulations", component: () => import("./views/RegulationsView.vue"), meta: { title: "演示法规库" } },
    { path: "/audit", component: () => import("./views/AuditView.vue"), meta: { title: "审计" } },
    { path: "/upload", redirect: "/documents" },
    { path: "/report/:id", redirect: (to) => `/reviews/${String(to.params.id)}` },
    { path: "/report", redirect: "/reviews" },
    { path: "/:pathMatch(.*)*", redirect: "/documents" },
  ],
});

router.afterEach((to) => {
  document.title = `${String(to.meta.title || "审核台")} · Compliance Desk`;
});

createApp(App).use(router).component("ToastHost", ToastHost).mount("#app");
