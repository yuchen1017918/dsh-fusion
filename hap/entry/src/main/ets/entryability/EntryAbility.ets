import { AbilityConstant, UIAbility, Want } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { window } from '@kit.ArkUI';

const TAG = 'DSHFusion';
const DOMAIN = 0x0001;

/**
 * 鸿蒙端入口。
 *
 * ── 这个分支现在到哪一步了（如实说）────────────────────────────────
 * 这是**骨架**：工程结构、清单、权限、启动流程都真实可编译，
 * 但端侧 Host（Node 运行时 + dsh 核心树）**还没接上**。
 *
 * 之所以先做骨架，是因为这条线上真正的未知数不在界面 —— 而在：
 *   · Node 能不能在鸿蒙沙箱里跑（要自建 jitless 构建）
 *   · 原生二进制的签名域（execve 只放行带 .codesign 的 ELF）
 *   · hmfs 禁 symlink 时工具链怎么解包
 * 这些在 `docs/HAP-PLAN.md` 里有详述。骨架的意义是先把**可编译的容器**立住，
 * 再把运行时一个一个接进去。
 */
export default class EntryAbility extends UIAbility {
  onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    hilog.info(DOMAIN, TAG, 'onCreate：bundle=%{public}s', this.context.applicationInfo.name);
  }

  onDestroy(): void {
    hilog.info(DOMAIN, TAG, 'onDestroy');
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    hilog.info(DOMAIN, TAG, 'onWindowStageCreate');
    windowStage.loadContent('pages/Index', (err) => {
      if (err.code) {
        hilog.error(DOMAIN, TAG, '加载首页失败：%{public}s', JSON.stringify(err));
        return;
      }
      hilog.info(DOMAIN, TAG, '首页已加载');
    });
  }

  onWindowStageDestroy(): void {
    hilog.info(DOMAIN, TAG, 'onWindowStageDestroy');
  }

  onForeground(): void {
    hilog.info(DOMAIN, TAG, 'onForeground');
  }

  onBackground(): void {
    hilog.info(DOMAIN, TAG, 'onBackground');
  }
}
