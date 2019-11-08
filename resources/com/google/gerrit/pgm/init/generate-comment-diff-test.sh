#!/bin/bash

tmp_stdout=$(mktemp -t generate-comment-diff-test-stdout.XXXXXX)
tmp_stderr=$(mktemp -t generate-comment-diff-test-stderr.XXXXXX)

function do_test
{
	local server
	local change_num
	local author_id
	local timestamp
	local expected_stdout

	server="$1"
	change_num="$2"
	author_id="$3"
	timestamp="$4"
	expected_stdout="$5"

	echo "Testing: ./generate-comment-diff.py \"$server\" \"$change_num\" \"$author_id\" \"$timestamp\""

	./generate-comment-diff.py "$server" "$change_num" "$author_id" "$timestamp" > "$tmp_stdout" 2> "$tmp_stderr"
	if [ "$?" -ne 0 ]; then
		echo "Script exited with non-zero exit code"
		exit 1
	fi

	diff -u "$expected_stdout" "$tmp_stdout"
	if [ "$?" -ne 0 ]; then
		echo "Script output does not match expected output"
		exit 1
	fi

	diff -u "/dev/null" "$tmp_stderr"
	if [ "$?" -ne 0 ]; then
		echo "Script has output on stderr"
		exit 1
	fi
}

gdb_gerrit="https://gnutoolchain-gerrit.osci.io/r"

do_test "$gdb_gerrit" 32 1000007 '2019-10-14 08:56:03.000000000' "comment-32-01.expected"
do_test "$gdb_gerrit" 32 1000010 '2019-10-21 20:52:28.000000000' "comment-32-02.expected"
do_test "$gdb_gerrit" 32 1000007 '2019-10-24 11:09:20.000000000' "comment-32-03.expected"
do_test "$gdb_gerrit" 32 1000007 '2019-10-24 11:15:53.000000000' "comment-32-04.expected"
do_test "$gdb_gerrit" 32 1000001 '2019-10-30 16:36:34.000000000' "comment-32-05.expected"
do_test "$gdb_gerrit" 32 1000007 '2019-10-30 17:35:29.000000000' "comment-32-06.expected"
do_test "$gdb_gerrit" 32 1000007 '2019-10-30 17:35:38.000000000' "comment-32-07.expected"
do_test "$gdb_gerrit" 32 1000010 '2019-10-31 01:35:05.000000000' "comment-32-08.expected"
do_test "$gdb_gerrit" 32 1000003 '2019-11-02 00:03:56.000000000' "comment-32-09.expected"

rm -f "$tmp_stdout" "$tmp_stderr"
