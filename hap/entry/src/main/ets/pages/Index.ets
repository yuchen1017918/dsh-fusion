import { deviceInfo } from '@kit.BasicServicesKit';
import { display } from '@kit.ArkUI';
import { BusinessError } from '@kit.BasicServicesKit';

/**
 * DSH Fusion · 鸿蒙端首屏。
 *
 * ── 为什么第一屏是「环境自检」而不是聊天界面 ────────────────────
 * 这条线上真正的未知数不在界面。端侧能不能跑起来，取决于几件**在写界面之前
 * 必须先确认**的事：
 *
 *   1. Node 运行时能不能在本应用进程内起来（要自建 jitless 构建）
 *   2. 原生二进制能不能 execve（鸿蒙按**签名域**放行，与权限位无关）
 *   3. 工具链归档能不能解开（hmfs 禁 symlink，解包会失败）
 *
 * 所以第一屏先把「这台设备是什么、当前实现到哪一步」摊开说清楚 ——
 * **界面不撒谎**是这个项目的一条设计原则：不可用的动作要把原因写在旁边，
 * 而不是给一个点了没反应的入口。
 */
interface CheckRow {
  name: string;
  value: string;
  state: 'ok' | 'warn' | 'todo';
}

@Entry
@Component
struct Index {
  @State rows: CheckRow[] = [];
  @State ready: boolean = false;

  aboutToAppear(): void {
    this.collect();
  }

  /** 收集设备与运行时的真实信息 —— 能测的一律测，不猜。 */
  private collect(): void {
    const out: CheckRow[] = [];

    const push = (name: string, value: string, state: 'ok' | 'warn' | 'todo') => {
      out.push({ name, value, state });
    };

    // ── 设备 ──
    push('设备', `${deviceInfo.brand} ${deviceInfo.productModel}`, 'ok');
    push('系统', `${deviceInfo.osFullName}（API ${deviceInfo.sdkApiVersion}）`, 'ok');
    // deviceInfo 上没有 cpuFrequency —— ArkTS 的类型检查直接拦住了这个错误，
    // 这正是「编译通过」有价值的地方：它比运行时才发现早得多。
    push('CPU', `${deviceInfo.abiList}`, 'ok');

    // 屏幕：形态路由（手机态 vs 桌面态）要靠它决定，与 HDSH 的 600vp 分界同一思路
    try {
      const d = display.getDefaultDisplaySync();
      // px2vp 已废弃；vp = px / 密度，自己算即可（display 给的是 densityPixels）
      const density = d.densityPixels > 0 ? d.densityPixels : 1;
      const vpWidth = d.width / density;
      const form = vpWidth >= 600 ? '桌面/平板态' : '手机态';
      push('屏幕', `${d.width}×${d.height} px · ${Math.round(vpWidth)} vp → ${form}`, 'ok');
    } catch (e) {
      const err = e as BusinessError;
      push('屏幕', `读不到（${err.message}）`, 'warn');
    }

    // ── 能力自检：这几条才是这条线上的真问题 ──

    // 1. 内存 —— 端侧要跑 Node + 内核树，小内存机型会直接被系统杀掉
    push('端侧 Host', '未接入（Node 运行时尚未随包分发）', 'todo');

    // 2. 签名域 —— 端侧 execve 只放行自己签名域内的 ELF，
    //    所以工具链必须在构建期自签名。这条是鸿蒙与 Android 最大的差别。
    push('原生二进制', '未接入（构建期自签名流程尚未落地）', 'todo');

    // 3. 文件系统 —— hmfs 禁 symlink，解包工具链归档会失败
    push('文件系统', 'hmfs 禁 symlink，解包需复制补齐（未实现）', 'todo');

    push('工作区', '未接入', 'todo');

    this.rows = out;
    this.ready = true;
  }

  private stateColor(s: string): string {
    if (s === 'ok') return '#3DDC97';
    if (s === 'warn') return '#FFC24B';
    return '#7C8DA8';
  }

  private stateText(s: string): string {
    if (s === 'ok') return '就绪';
    if (s === 'warn') return '注意';
    return '待实现';
  }

  build() {
    Column() {
      // ── 头部 ──
      Column() {
        Text('DSH Fusion')
          .fontSize(26)
          .fontWeight(FontWeight.Medium)
          .fontColor('#F2F8FF')
        Text('鸿蒙端 · 骨架阶段')
          .fontSize(13)
          .fontColor('#8FA3BF')
          .margin({ top: 6 })
      }
      .alignItems(HorizontalAlign.Start)
      .width('100%')
      .padding({ bottom: 20 })

      // ── 环境自检 ──
      Text('环境自检')
        .fontSize(12)
        .fontColor('#5B8CFF')
        .letterSpacing(1.2)
        .width('100%')
        .margin({ bottom: 10 })

      Column() {
        ForEach(this.rows, (row: CheckRow) => {
          Column() {
            Row() {
              Text(row.name)
                .fontSize(13)
                .fontColor('#8FA3BF')
                .width(88)
              Text(row.value)
                .fontSize(13)
                .fontColor('#D7E3F4')
                .layoutWeight(1)
              Text(this.stateText(row.state))
                .fontSize(11)
                .fontColor(this.stateColor(row.state))
            }
            .width('100%')
            .alignItems(VerticalAlign.Top)
          }
          .width('100%')
          .padding({ top: 9, bottom: 9 })
          .border({ width: { bottom: 1 }, color: '#141C29' })
        }, (row: CheckRow) => row.name)
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 6, bottom: 6 })
      .backgroundColor('#131A26')
      .borderRadius(14)
      .border({ width: 1, color: '#1E2A3E' })

      // ── 下一步（界面不撒谎：不可用的动作把原因写在旁边）──
      Text('下一步')
        .fontSize(12)
        .fontColor('#5B8CFF')
        .letterSpacing(1.2)
        .width('100%')
        .margin({ top: 22, bottom: 10 })

      Column() {
        Text('这个应用现在还跑不起来聊天 —— 端侧 Host 还没接上。')
          .fontSize(13)
          .fontColor('#C9D6E8')
        Text('瓶颈不在界面，在三件事：Node 的 jitless 构建、原生二进制的签名域、' +
             '以及 hmfs 禁 symlink 时的工具链解包。完整论证见仓库的 docs/HAP-PLAN.md。')
          .fontSize(12.5)
          .fontColor('#8FA3BF')
          .margin({ top: 8 })
        Text('现在最省事的选择：用社区已有的 dshm（已签名 HAP，306 MB，' +
             '支持手机/折叠屏/平板/2in1）。')
          .fontSize(12.5)
          .fontColor('#3DDC97')
          .margin({ top: 10 })
      }
      .alignItems(HorizontalAlign.Start)
      .width('100%')
      .padding(16)
      .backgroundColor('#171208')
      .borderRadius(14)
      .border({ width: 1, color: '#5A4620' })
    }
    .width('100%')
    .height('100%')
    .padding(20)
    .backgroundColor('#0B0E14')
    .alignItems(HorizontalAlign.Start)
  }
}
