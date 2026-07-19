import { createApp } from "vue";
import { createRouter, createWebHashHistory } from "vue-router";
import App from "./App.vue";
import UploadView from "./views/UploadView.vue";
import ReportView from "./views/ReportView.vue";
import ToastHost from "./components/ToastHost.vue";
import "./styles.css";

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: "/", redirect: "/upload" },
    { path: "/upload", name: "upload", component: UploadView, meta: { title: "文档上传" } },
    { path: "/report", name: "report-new", component: ReportView, meta: { title: "审核报告" } },
    { path: "/report/:id", name: "report", component: ReportView, meta: { title: "审核报告" } },
  ],
});

createApp(App).use(router).component("ToastHost", ToastHost).mount("#app");
