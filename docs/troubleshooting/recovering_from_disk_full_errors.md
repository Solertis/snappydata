<a id="recovering_from_disk_full_errors"></a>

# Recovering from Disk Full Errors

If a member of your SnappyData distributed system fails due to a disk full error condition, add or make additional disk capacity available and attempt to restart the member normally. If the member does not restart and there is a redundant copy of its tables in a disk store on another member, you can restore the member using the following steps:

1.  Delete or move the disk store files from the failed member.

2.  Use the [list-missing-disk-stores](../reference/command_line_utilities/store-list-missing-disk-stores.md) snappy-shell command to identify any missing data. You may need to manually restore this data.

3.  Revoke the member using the [revoke-disk-store](../reference/command_line_utilities/store-revoke-missing-disk-stores.md) command.

4.  Restart the member.