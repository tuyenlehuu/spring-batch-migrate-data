package com.realbank.transactionmigration.dto;

import java.util.List;

public class UpsertFinTxtRequest {

    private List<FinTxtItemPayload> items;

    public UpsertFinTxtRequest() {
    }

    public UpsertFinTxtRequest(List<FinTxtItemPayload> items) {
        this.items = items;
    }

    public List<FinTxtItemPayload> getItems() {
        return items;
    }

    public void setItems(List<FinTxtItemPayload> items) {
        this.items = items;
    }
}
