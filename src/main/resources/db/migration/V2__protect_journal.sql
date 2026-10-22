-- Protect committed journal/history rows from accidental edits and deletes.
-- Database owner/superuser privileges can bypass these controls; use a separate
-- restricted application role when deploying beyond a local demo.
CREATE FUNCTION deny_financial_history_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Financial history is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ledger_append_only BEFORE UPDATE OR DELETE ON ledger_entry
FOR EACH ROW EXECUTE FUNCTION deny_financial_history_mutation();
CREATE TRIGGER operation_append_only BEFORE UPDATE OR DELETE ON payment_operation
FOR EACH ROW EXECUTE FUNCTION deny_financial_history_mutation();

-- Validate double-entry equality at COMMIT, after both entries are inserted.
CREATE FUNCTION verify_balanced_operation() RETURNS trigger AS $$
DECLARE debit_sum BIGINT; credit_sum BIGINT; entry_count BIGINT;
BEGIN
    SELECT COUNT(*), COALESCE(SUM(amount_paise) FILTER (WHERE entry_type = 'DEBIT'), 0),
           COALESCE(SUM(amount_paise) FILTER (WHERE entry_type = 'CREDIT'), 0)
    INTO entry_count, debit_sum, credit_sum FROM ledger_entry WHERE operation_id = NEW.id;
    IF entry_count <> 2 OR debit_sum <> NEW.amount_paise OR credit_sum <> NEW.amount_paise THEN
        RAISE EXCEPTION 'Operation must have two balanced journal entries';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE CONSTRAINT TRIGGER operation_balanced AFTER INSERT ON payment_operation
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_balanced_operation();
