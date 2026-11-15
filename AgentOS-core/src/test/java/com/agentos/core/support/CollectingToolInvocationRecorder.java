package com.agentos.core.support;

import com.agentos.core.audit.ToolInvocationRecord;
import com.agentos.core.audit.ToolInvocationRecorder;
import java.util.ArrayList;
import java.util.List;

public final class CollectingToolInvocationRecorder implements ToolInvocationRecorder {
    public final List<ToolInvocationRecord> records = new ArrayList<>();

    public ToolInvocationRecord begin(ToolInvocationRecord record) {
        records.add(record);
        return record;
    }

    public void complete(ToolInvocationRecord record) {
        records.add(record);
    }

    public void skipped(ToolInvocationRecord record) {
        records.add(record);
    }
}
