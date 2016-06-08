/**
 * Copyright 2012-2016 Rafal Lewczuk <rafal.lewczuk@jitlogic.com>
 * <p/>
 * This is free software. You can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 * <p/>
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * <p/>
 * You should have received a copy of the GNU General Public License
 * along with this software. If not, see <http://www.gnu.org/licenses/>.
 */

package com.jitlogic.zorka.core.spy;

import com.jitlogic.zorka.common.cbor.CBORConstants;
import com.jitlogic.zorka.common.tracedata.SymbolRegistry;
import com.jitlogic.zorka.common.util.ZorkaUtil;

import static com.jitlogic.zorka.common.util.ZorkaUnsafe.*;


/**
 * Efficient implementation of trace handler that produces CBOR data strea,.
 * See docs/tracer/TRACE_DATA_FORMAT.md for detailed data format description.
 *
 * This implementation is (almost) GC-free and incurs minimum performance penalty.
 *
 * @author Rafal Lewczuk
 */
public class TraceRecorder extends TraceBuffer implements TraceHandler {


    public static final int STACK_DEFAULT_SIZE = 256;

    public static final int TICK_SHIFT = 16;
    public static final int BPOS_SHIFT = 32;

    public static final int TRACEID_BITS = 16;
    public static final long TRACEID_MASK = 0x00FFFF0000000000L;

    public static final int  TSTAMP_BITS = 40;
    public static final long TSTAMP_MASK = 0x000000FFFFFFFFFFL;

    public static final int  CALLS_BITS = 32;
    public static final long CALLS_MASK = 0x00000000FFFFFFFFL;

    public static final int  TPOS_BITS  = 32;
    public static final long TPOS_MASK  = 0xFFFFFFFF00000000L;

    public static final long TF_SUBMIT_TRACE  = 0x0100000000000000L;
    public static final long TF_SUBMIT_METHOD = 0x0200000000000000L;

    public static final long TSTART_FUZZ = System.currentTimeMillis() - (System.nanoTime() / 1000000L);

    private long minMethodTime = 4;   // Default minimum duration = 4 ticks = ~250us

    private long minTraceTime = 16777216; // Approximately 1s

    protected SymbolRegistry symbols;

    /**
     * Contains 'image' of traced thread call stack containing basic information needed to manage tracing process.
     * Each stack frame is represented by two entries:
     * Entry 0: [tstamp|tid|flags]
     * Entry 1: [calls|bufpos]
     *
     * Where:
     * tstamp [40 bit] - timestamp (ticks, 65536ns each)
     * tid    [16 bit] - trace ID  (only for frames marking trace beginning)
     * flags  [8 bit]  - additional flags: TF_ERROR [1], TF_SUBMIT_TRACE[2], TF_SPLIT [4]
     * calls  [24 bit] - counts (encountered) calls;
     * bufpos [32bit]  - start position in output buffer (used in
     */
    private long[] stack = new long[STACK_DEFAULT_SIZE];

    /** Position of first unused slot in stack[]. If non-zero, Entry0 is at [stackPos-2], Entry1 is at [stackPos-1]. */
    private int stackPos = 0;

    /** Stack length is kept separately, so we avoid indirection when refering to it. */
    private int stackLen = STACK_DEFAULT_SIZE;

    private boolean disabled = false;

    /** Identity hash code of last seen exception. */
    private int exceptionId;


    public TraceRecorder(TraceBufManager bufManager, SymbolRegistry symbols, TraceBufOutput output) {
        super(bufManager);
        this.symbols = symbols;
        this.output = output;
    }


    public void traceEnter(int methodId) {
        if (disabled) return;

        disabled = true;

        if (stackPos == 1 && 0 == (stack[0] >> TSTAMP_BITS)) {
            bufPos = 0;
        } else if (stackLen - stackPos < 2) {
            extendStack();
        }

        if (bufLen - bufPos < 12) {
            nextChunk();
        }

        long tst = ticks();
        long tr0 = tst | ((long)methodId << TSTAMP_BITS);

        UNSAFE.putInt(buffer, BYTE_ARRAY_OFFS+bufPos, TraceFormat.TREC_HEADER);
        UNSAFE.putLong(buffer, BYTE_ARRAY_OFFS+bufPos+4, tr0);

        stack[stackPos] = tst;
        stack[stackPos+1] = 1 + ((long)(bufPos + bufOffs) << 32);

        bufPos += 12;
        stackPos += 2;

        disabled = false;
    }


    public void traceReturn() {
        if (disabled) return;
        if (stackPos == 0) return;

        disabled = true;

        long tst = ticks();

        long sp1 = stack[stackPos-1];
        long sp2 = stack[stackPos-2];

        long dur = tst - (sp2 & TSTAMP_MASK);
        int tid = (int)((sp2 & TRACEID_MASK) >> TRACEID_BITS);
        int pos = (int)(sp1 >> TPOS_BITS);
        long calls = sp1 & CALLS_MASK;

        boolean fsm = 0 != (sp2 & TF_SUBMIT_METHOD);

        if (dur >= minMethodTime || fsm || pos < bufOffs) {
            if (calls < 0x1000000) {
                if (bufLen - bufPos < 10) nextChunk();
                tst |= (calls << TSTAMP_BITS);
                buffer[bufPos]   = (byte) (CBORConstants.BYTES_BASE+8);
                UNSAFE.putLong(buffer, BYTE_ARRAY_OFFS+bufPos+1, tst);
                buffer[bufPos+9] = (byte) CBORConstants.BREAK_CODE;
                bufPos += 10;
            } else {
                if (bufLen - bufPos < 18) nextChunk();
                buffer[bufPos]   = (byte) CBORConstants.BYTES_BASE+16;
                long p = BYTE_ARRAY_OFFS+bufPos+1;
                UNSAFE.putLong(buffer, p, tst);
                UNSAFE.putLong(buffer, p+8, calls);
                buffer[bufPos+17] = (byte) CBORConstants.BREAK_CODE;
                bufPos += 18;
            }
        } else {
            bufPos = pos - bufOffs;
        }

        stackPos -= 2;

        if (tid != 0) {
            if (dur >= minTraceTime || 0 != (sp2 & TF_SUBMIT_TRACE)) {
                flush();
            }
        }

        if (stackPos > 0) {
            long spp = stack[stackPos-1];
            stack[stackPos-1] =  ((spp & CALLS_MASK) + calls) | (spp & TPOS_MASK);
            if (fsm) {
                stack[stackPos - 2] |= TF_SUBMIT_METHOD;
            }
        } else  {
            if (bufOffs == 0) {
                bufPos = 0;
            } else {
                dropTrace();
            }
        }

        disabled = false;
    }


    public void serializeException(Throwable e) {
        if (bufLen - bufPos < 3) nextChunk();

        int id = System.identityHashCode(e);

        if (id == exceptionId) {
            // Known (already written) exception - save reference to it;
            writeUInt(CBORConstants.TAG_BASE, TraceFormat.TAG_EXCEPTION_REF);
            writeUInt(CBORConstants.UINT_BASE, id);
        } else {
            // Unknown exception
            buffer[bufPos] = (byte) (CBORConstants.TAG_CODE1);
            buffer[bufPos + 1] = (byte) TraceFormat.TAG_EXCEPTION;
            buffer[bufPos + 2] = (byte) (CBORConstants.ARR_CODE0 + 5);
            bufPos += 3;

            // Object identity (useful for determining
            writeInt(id);

            // Class name (as string ref)
            if (bufLen - bufPos < 1) nextChunk();
            buffer[bufPos++] = (byte) (CBORConstants.TAG_CODE0 + TraceFormat.TAG_STRING_REF);
            writeUInt(CBORConstants.UINT_CODE0, symbols.symbolId(e.getClass().getName()));

            // Message
            writeString(e.getMessage());

            // Stack trace
            StackTraceElement[] stack = e.getStackTrace();
            writeUInt(CBORConstants.ARR_BASE, stack.length);
            for (StackTraceElement el : stack) {
                writeUInt(CBORConstants.ARR_BASE, 4);
                writeStringRef(el.getClassName());
                writeStringRef(el.getMethodName());
                writeStringRef(el.getFileName());
                writeInt(el.getLineNumber());
            }

            // Cause
            Throwable cause = e.getCause();
            if (cause != null) {
                serializeException(cause);
            } else {
                write(CBORConstants.NULL_CODE);
            }
        }
    }


    public void traceError(Throwable e) {
        if (disabled) return;
        if (stackPos == 0) return;

        serializeException(e);
        exceptionId = System.identityHashCode(e);

        stack[stackPos-2] |= TF_SUBMIT_METHOD;

        traceReturn();
    }


    @Override
    public void traceBegin(int traceId, long clock, int flags) {
        if (disabled) return;
        if (stackPos == 0) return;

        long sp2 = stack[stackPos-2];
        stack[stackPos-2] = (sp2 & TSTAMP_MASK) | ((long)traceId << TSTAMP_BITS);

        // TODO stack[stackPos-2] |= ((long)traceId << TSTAMP_BITS)  -- will work if traceId is always 0
        // TODO eventually merge traceBegin with traceEnter

        writeUInt(CBORConstants.TAG_BASE, TraceFormat.TAG_TRACE_BEGIN);
        writeUInt(CBORConstants.ARR_BASE, 2);
        writeLong(clock);
        writeInt(traceId);
    }


    @Override
    public Object getAttr(int attrId) {
        // TODO eventually implement version with non-guaranteed result
        throw new RuntimeException("Not implemented.");
    }


    @Override
    public Object getAttr(int traceId, int attrId) {
        // TODO eventually implement version with non-guaranteed result
        throw new RuntimeException("Not implemented.");
    }


    @Override
    public void newAttr(int traceId, int attrId, Object attrVal) {
        if (disabled) return;

        if (bufLen - bufPos < 4) nextChunk();

        buffer[bufPos++] = (byte) (CBORConstants.TAG_CODE0 + TraceFormat.TAG_TRACE_ATTR);
        if (traceId != 0) {
            buffer[bufPos++] = (byte) (CBORConstants.MAP_CODE0+1);
            writeUInt(0,traceId);
            if (bufLen - bufPos < 4) nextChunk();
        }
        buffer[bufPos++] = (byte) (CBORConstants.MAP_CODE0+1);
        stack[stackPos-2] |= TF_SUBMIT_METHOD;

        writeUInt(0,attrId);
        writeObject(attrVal);
    }


    @Override
    public void disable() {
        // TODO dopracować disabled bo niekompletny
        disabled = true;
    }


    @Override
    public void enable() {
        disabled = false;
    }


    @Override
    public void setMinimumTraceTime(long minTraceTime) {
        this.minTraceTime = minTraceTime;
    }


    @Override
    public void markTraceFlags(int traceId, int flag) {

    }


    @Override
    public boolean isInTrace(int traceId) {
        return false;
    }



    @Override
    public void close() {
        // TODO
    }

    private void extendStack() {
        stack = ZorkaUtil.clipArray(stack, stack.length * 2);
        stackLen = stack.length;
    }


    /** Returns current nano time. */
    protected long ticks() {
        return System.nanoTime() >> TICK_SHIFT;
    }

    /** Returns current wallclock time. */
    protected long clock() { return System.currentTimeMillis(); }


    protected void writeStringRef(String s) {
        writeUInt(CBORConstants.TAG_BASE, TraceFormat.TAG_STRING_REF);
        writeUInt(CBORConstants.UINT_BASE, symbols.symbolId(s));
    }


}