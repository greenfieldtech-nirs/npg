-- Links an operator to its "allocated to" name in the national numbering plan (np_numbering_plan),
-- so the plan can decide who natively holds a number when no np_number_ranges entry covers it.
-- Unique: one plan name identifies at most one operator.
ALTER TABLE np_operators
  ADD COLUMN numbering_plan_name VARCHAR(255) NULL,
  ADD UNIQUE KEY uq_np_operators_numbering_plan_name (numbering_plan_name);
