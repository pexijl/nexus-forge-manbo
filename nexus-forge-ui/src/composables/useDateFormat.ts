import dayjs from 'dayjs';
import 'dayjs/locale/zh-cn';
import relativeTime from 'dayjs/plugin/relativeTime';

dayjs.locale('zh-cn');
dayjs.extend(relativeTime);

/**
 * 日期格式化工具
 * 处理 ISO 8601 格式：yyyy-MM-DDTHH:mm:ss.sssZ
 */
export function useDateFormat() {
    /**
     * 完整日期：YYYY年M月D日
     */
    function formatDate(iso: string | undefined | null): string {
        if (!iso) return '-';
        return dayjs(iso).format('YYYY年M月D日');
    }

    /**
     * 日期+时间： YYYY年M月D日 HH:mm
     */
    function formatDateTime(iso: string | undefined | null): string {
        if (!iso) return '-';
        return dayjs(iso).format('YYYY年M月D日 HH:mm');
    }

    /**
     * 相对时间：n天前 / 刚刚
     */
    function fromNow(iso: string | undefined | null): string {
        if (!iso) return '-';
        return dayjs(iso).fromNow();
    }

    /**
     * 友好显示：今天 HH:mm / 昨天 / YYYY年M月D日
     */
    function friendly(iso: string | undefined | null): string {
        if (!iso) return '-';
        const d = dayjs(iso);
        const now = dayjs();

        if (d.isSame(now, 'day')) return d.format('今天 HH:mm');
        if (d.isSame(now.subtract(1, 'day'), 'day')) return '昨天';
        if (d.isSame(now, 'year')) return d.format('M月D日');
        return d.format('YYYY年M月D日');
    }

    /**
     * 原始 ISO 转本地时间字符串
     */
    function toLocalString(iso: string | undefined | null): string {
        if (!iso) return '-';
        return dayjs(iso).format('YYYY-MM-DD HH:mm:ss');
    }

    return {
        formatDate,
        formatDateTime,
        fromNow,
        friendly,
        toLocalString,
    };
}