package com.wimoor.common;

import java.text.FieldPosition;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 线程安全的 SimpleDateFormat。
 *
 * <p>{@link SimpleDateFormat} 内部持有可变的 Calendar 状态，作为 static 共享时，
 * 多线程并发调用 format/parse 会产生串日期、错乱甚至 NumberFormatException。
 * 本类保持 SimpleDateFormat 的类型与全部对外 API 不变（调用方无需任何改动），
 * 内部通过 ThreadLocal 为每个线程提供独立实例，从根源消除共享状态竞争。</p>
 *
 * <p>用法：把原来的 {@code new SimpleDateFormat(pattern)} 直接替换为
 * {@code new ThreadSafeSimpleDateFormat(pattern)} 即可，字段类型仍可声明为 SimpleDateFormat。</p>
 */
public class ThreadSafeSimpleDateFormat extends SimpleDateFormat {
    private static final long serialVersionUID = 1L;

    private final String pattern;
    private final Locale locale;
    private final TimeZone timeZone;
    private final ThreadLocal<SimpleDateFormat> delegate;

    public ThreadSafeSimpleDateFormat(String pattern) {
        super(pattern);
        this.pattern = pattern;
        this.locale = Locale.getDefault(Locale.Category.FORMAT);
        this.timeZone = super.getTimeZone();
        this.delegate = ThreadLocal.withInitial(() -> {
            SimpleDateFormat f = new SimpleDateFormat(pattern, locale);
            f.setTimeZone(timeZone);
            f.setLenient(this.isLenient());
            return f;
        });
    }

    @Override
    public StringBuffer format(Date date, StringBuffer toAppendTo, FieldPosition pos) {
        return delegate.get().format(date, toAppendTo, pos);
    }

    @Override
    public Date parse(String source, ParsePosition pos) {
        return delegate.get().parse(source, pos);
    }
}
