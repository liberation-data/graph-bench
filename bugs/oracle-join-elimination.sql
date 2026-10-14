-- Oracle AI Database 26ai Free, 23.26.3.0.0 (gvenzl/oracle-free:23-faststart)
--
-- A join to a table that the query filters is eliminated, and the filter with it, when
--   * a foreign key makes the join one the optimizer may eliminate, and
--   * the filter is JSON_VALUE of a JSON column, with a function-based index over that expression.
--
-- With the value written into the statement the answer is wrong: every row, as if there were no
-- filter. With the value bound, the server process ends: ORA-03113 to the client, and
-- ORA-07445 [kkocfbMarkBindFroCB] in the alert log.
--
-- It takes the index. Without it the answer is right, and it is right with a function-based index
-- over a plain column (UPPER(name)). It needs no property graph, though a GRAPH_TABLE over such
-- tables meets it as soon as a pattern starts from a node found by a property.
--
-- Run as any user with CREATE TABLE:  sqlplus user/password@//host/FREEPDB1 @oracle-join-elimination.sql

set lines 160 pages 100 echo on

drop table if exists je_edge purge;
drop table if exists je_node purge;

create table je_node (nid number generated always as identity primary key, props json not null);
create table je_edge (eid number generated always as identity primary key,
                      src number not null references je_node (nid));

insert into je_node (props) select json_object('id' value 'p' || level) from dual connect by level <= 1000;
insert into je_edge (src) select nid from je_node, (select level from dual connect by level <= 3);
commit;

prompt == 1. No index: 3, which is right. Node p7 has three edges.
select count(*) from je_edge e join je_node n on n.nid = e.src where json_value(n.props, '$.id') = 'p7';

create index je_node_id on je_node (json_value(props, '$.id'));

prompt == 2. With the index: 3000. The filter is gone.
select count(*) from je_edge e join je_node n on n.nid = e.src where json_value(n.props, '$.id') = 'p7';

prompt == 3. Its plan: JE_NODE is not read, and no predicate tests the id.
explain plan for
select count(*) from je_edge e join je_node n on n.nid = e.src where json_value(n.props, '$.id') = 'p7';
select * from table(dbms_xplan.display(format => 'BASIC +PREDICATE'));

prompt == 4. With join elimination off: 3 again.
select /*+ opt_param('_optimizer_join_elimination_enabled' 'false') */ count(*)
from je_edge e join je_node n on n.nid = e.src where json_value(n.props, '$.id') = 'p7';

prompt == 5. With the value bound: the server process ends (ORA-03113).
variable id varchar2(20)
exec :id := 'p7'
select count(*) from je_edge e join je_node n on n.nid = e.src where json_value(n.props, '$.id') = :id;
