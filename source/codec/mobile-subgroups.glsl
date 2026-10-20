// Pyrowave's quantization and packing call these with complete 8/16-lane clusters.
// XOR partners stay in that cluster, including when other clusters have returned.
uint pwClusteredAdd(uint value, uint count) {
    for (uint offset=1u;offset<count;offset<<=1u) value+=subgroupShuffleXor(value,offset);
    return value;
}
int pwClusteredAdd(int value, uint count) {
    for (uint offset=1u;offset<count;offset<<=1u) value+=subgroupShuffleXor(value,offset);
    return value;
}
float pwClusteredAdd(float value, uint count) {
    for (uint offset=1u;offset<count;offset<<=1u) value+=subgroupShuffleXor(value,offset);
    return value;
}
float pwClusteredMax(float value, uint count) {
    for (uint offset=1u;offset<count;offset<<=1u) value=max(value,subgroupShuffleXor(value,offset));
    return value;
}
