CREATE TABLE orders (
	id              TEXT    PRIMARY KEY,
	idempotency_key TEXT    NOT NULL UNIQUE,
	customer_id     TEXT    NOT NULL,
	status          TEXT    NOT NULL CHECK (status IN ('CREATED', 'PAID')),
	total_amount    INTEGER NOT NULL CHECK (total_amount >= 0),
	created_at      TEXT    NOT NULL,
	updated_at      TEXT    NOT NULL
);

CREATE TABLE order_lines (
	id           TEXT    PRIMARY KEY,
	order_id     TEXT    NOT NULL REFERENCES orders (id),
	product_id   TEXT    NOT NULL,
	product_name TEXT    NOT NULL,
	unit_price   INTEGER NOT NULL CHECK (unit_price >= 0),
	quantity     INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 99),
	line_amount  INTEGER NOT NULL CHECK (line_amount = unit_price * quantity),
	UNIQUE (order_id, product_id)
);

CREATE INDEX idx_order_lines_order_id ON order_lines (order_id);
