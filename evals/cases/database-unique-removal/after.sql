create table orders (
  id bigint primary key,
  operation_key varchar(100) not null,
  amount numeric(19,2) not null
);
