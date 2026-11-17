package net.sourceforge.servestream.utils;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal decoder for Android's protobuf tombstone format (Android 12+),
 * extracting the signal, abort message, crashing thread backtrace and recent log lines.
 */
final class TombstoneParser {

    private TombstoneParser() {
    }

    private static final class Field {
        int number;
        int type;
        long varint;
        int start;
        int end;
    }

    private static final class Reader {
        final byte[] b;
        int pos;
        final int end;

        Reader(byte[] b, int start, int end) {
            this.b = b;
            this.pos = start;
            this.end = end;
        }

        long varint() {
            long result = 0;
            int shift = 0;
            while (pos < end) {
                int c = b[pos++] & 0xff;
                result |= (long) (c & 0x7f) << shift;
                if ((c & 0x80) == 0) {
                    return result;
                }
                shift += 7;
            }
            throw new IllegalStateException("truncated varint");
        }

        Field next() {
            if (pos >= end) {
                return null;
            }
            Field f = new Field();
            long key = varint();
            f.number = (int) (key >>> 3);
            f.type = (int) (key & 7);
            switch (f.type) {
                case 0:
                    f.varint = varint();
                    break;
                case 1:
                    pos += 8;
                    break;
                case 2:
                    int len = (int) varint();
                    f.start = pos;
                    f.end = pos + len;
                    if (f.end > end || len < 0) {
                        throw new IllegalStateException("bad length");
                    }
                    pos = f.end;
                    break;
                case 5:
                    pos += 4;
                    break;
                default:
                    throw new IllegalStateException("bad wire type " + f.type);
            }
            return f;
        }
    }

    private static String str(byte[] b, Field f) {
        try {
            return new String(b, f.start, f.end - f.start, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    static String parse(byte[] b) {
        StringBuilder out = new StringBuilder();
        long crashTid = -1;
        List<Field> threads = new ArrayList<Field>();
        List<Field> logBuffers = new ArrayList<Field>();

        Reader r = new Reader(b, 0, b.length);
        Field f;
        while ((f = r.next()) != null) {
            switch (f.number) {
                case 6:
                    if (f.type == 0) crashTid = f.varint;
                    break;
                case 9:
                    if (f.type == 2) out.append("cmd: ").append(str(b, f)).append('\n');
                    break;
                case 10:
                    if (f.type == 2) out.append(signal(b, f)).append('\n');
                    break;
                case 14:
                    if (f.type == 2) out.append("Abort message: ").append(str(b, f)).append('\n');
                    break;
                case 15:
                    if (f.type == 2) {
                        Reader cr = new Reader(b, f.start, f.end);
                        Field cf;
                        while ((cf = cr.next()) != null) {
                            if (cf.number == 1 && cf.type == 2) out.append("Cause: ").append(str(b, cf)).append('\n');
                        }
                    }
                    break;
                case 16:
                    if (f.type == 2) threads.add(f);
                    break;
                case 18:
                    if (f.type == 2) logBuffers.add(f);
                    break;
                default:
                    break;
            }
        }

        out.append("crash tid: ").append(crashTid).append('\n');

        for (Field entry : threads) {
            Reader er = new Reader(b, entry.start, entry.end);
            Field ef;
            long key = -1;
            Field value = null;
            while ((ef = er.next()) != null) {
                if (ef.number == 1 && ef.type == 0) key = ef.varint;
                if (ef.number == 2 && ef.type == 2) value = ef;
            }
            if (value != null && key == crashTid) {
                out.append(thread(b, value));
            }
        }

        for (Field lb : logBuffers) {
            out.append(logs(b, lb));
        }

        return out.toString();
    }

    private static String signal(byte[] b, Field s) {
        Reader r = new Reader(b, s.start, s.end);
        Field f;
        String name = "", code = "";
        long addr = 0;
        while ((f = r.next()) != null) {
            if (f.number == 2 && f.type == 2) name = str(b, f);
            if (f.number == 4 && f.type == 2) code = str(b, f);
            if (f.number == 9 && f.type == 0) addr = f.varint;
        }
        return "signal " + name + " (" + code + ") fault addr 0x" + Long.toHexString(addr);
    }

    private static String thread(byte[] b, Field t) {
        StringBuilder sb = new StringBuilder();
        Reader r = new Reader(b, t.start, t.end);
        Field f;
        int n = 0;
        while ((f = r.next()) != null) {
            if (f.number == 2 && f.type == 2) {
                sb.append("thread: ").append(str(b, f)).append("\nbacktrace:\n");
            } else if (f.number == 4 && f.type == 2 && n < 40) {
                sb.append(frame(b, f, n++)).append('\n');
            }
        }
        return sb.toString();
    }

    private static String frame(byte[] b, Field fr, int n) {
        Reader r = new Reader(b, fr.start, fr.end);
        Field f;
        long relPc = 0, off = 0;
        String fn = "", file = "";
        while ((f = r.next()) != null) {
            if (f.number == 1 && f.type == 0) relPc = f.varint;
            if (f.number == 4 && f.type == 2) fn = str(b, f);
            if (f.number == 5 && f.type == 0) off = f.varint;
            if (f.number == 6 && f.type == 2) file = str(b, f);
        }
        int slash = file.lastIndexOf('/');
        String shortFile = slash >= 0 ? file.substring(slash + 1) : file;
        return "#" + n + " pc " + Long.toHexString(relPc) + " " + shortFile
                + (fn.length() > 0 ? " (" + fn + "+" + off + ")" : "");
    }

    private static String logs(byte[] b, Field lb) {
        Reader r = new Reader(b, lb.start, lb.end);
        Field f;
        String name = "";
        List<String> lines = new ArrayList<String>();
        while ((f = r.next()) != null) {
            if (f.number == 1 && f.type == 2) name = str(b, f);
            if (f.number == 2 && f.type == 2) {
                Reader mr = new Reader(b, f.start, f.end);
                Field mf;
                String tag = "", msg = "";
                long tid = 0, prio = 0;
                while ((mf = mr.next()) != null) {
                    if (mf.number == 3 && mf.type == 0) tid = mf.varint;
                    if (mf.number == 4 && mf.type == 0) prio = mf.varint;
                    if (mf.number == 5 && mf.type == 2) tag = str(b, mf);
                    if (mf.number == 6 && mf.type == 2) msg = str(b, mf);
                }
                lines.add(tid + " " + "??VDIWEF".charAt((int) Math.min(prio, 7)) + " " + tag + ": " + msg.trim());
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("--- log ").append(name).append(" ---\n");
        int from = Math.max(0, lines.size() - 40);
        for (int i = from; i < lines.size(); i++) {
            sb.append(lines.get(i)).append('\n');
        }
        return sb.toString();
    }
}
