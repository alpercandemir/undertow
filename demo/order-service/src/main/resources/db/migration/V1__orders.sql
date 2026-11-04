create table orders (
  operation_key varchar(100) primary key,
  amount numeric(19,2) not null check (amount >= 0),
  status varchar(20) not null check (status = 'SUCCESS')
);
create table order_events (
  operation_key varchar(100) primary key references orders(operation_key),
  event_type varchar(20) not null
);
